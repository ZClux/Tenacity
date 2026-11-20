package tenacity.inject;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.*;

/** Schema-preserving implementation of every injector kind used by Tenacity. */
final class PatchEngine implements Opcodes, ClassFileTransformer {
    static final String DISPATCH = "tenacity/inject/Dispatcher";
    static final String CI = "org/spongepowered/asm/mixin/injection/callback/CallbackInfo";
    static final String CIR = CI + "Returnable";
    final PortPlan plan;
    final ClassLoader game;
    final Set<Integer> covered = new HashSet<>();
    PatchEngine(PortPlan plan, ClassLoader game) { this.plan = plan; this.game = game; }

    byte[] apply(String owner, byte[] bytes) throws AnalyzerException {
        List<PortPlan.Patch> patches = plan.patches.get(owner);
        if (patches == null) return null;
        Set<Integer> applied = new HashSet<>();
        ClassNode node = Bytecode.read(bytes);
        for (MethodNode method : node.methods) {
            List<PortPlan.Patch> applicable = patches.stream().filter(p -> p.methods().stream().anyMatch(s -> PortPlan.methodMatches(s, method))).toList();
            if (applicable.isEmpty()) continue;
            AbstractInsnNode[] original = method.instructions.toArray();
            Frame<BasicValue>[] frames = new Analyzer<>(new BasicInterpreter(ASM9) {
                @Override public BasicValue newValue(Type t) {
                    return t != null && (t.getSort() == Type.OBJECT || t.getSort() == Type.ARRAY) ? new BasicValue(t) : super.newValue(t);
                }
                @Override public BasicValue merge(BasicValue a, BasicValue b) {
                    if (a.equals(b)) return a;
                    if (a.isReference() && b.isReference()) return BasicValue.REFERENCE_VALUE;
                    return BasicValue.UNINITIALIZED_VALUE;
                }
            }).analyze(owner, method);
            Map<AbstractInsnNode, List<PortPlan.Patch>> sites = new IdentityHashMap<>();
            for (PortPlan.Patch patch : applicable) {
                List<AbstractInsnNode> matched = sites(patch, original);
                if (matched.isEmpty()) continue;
                covered.add(patch.id());
                applied.add(patch.id());
                for (AbstractInsnNode site : matched) sites.computeIfAbsent(site, k -> new ArrayList<>()).add(patch);
            }
            for (int index = 0; index < original.length; index++) {
                AbstractInsnNode site = original[index];
                List<PortPlan.Patch> selected = sites.get(site);
                if (selected == null) continue;
                InsnList before = new InsnList(), after = new InsnList();
                List<PortPlan.Patch> calls = new ArrayList<>(), expressions = new ArrayList<>();
                for (PortPlan.Patch patch : selected) {
                    if (patch.kind().equals("Inject")) {
                        String at = (String) Bytecode.value(patch.at(), "value", "");
                        boolean returns = at.equals("RETURN") || at.equals("TAIL");
                        String[] shift = (String[]) Bytecode.value(patch.at(), "shift", new String[]{"", "NONE"});
                        inject(patch, method, frames[index], returns, shift[1].equals("AFTER") ? after : before);
                    } else if (patch.kind().equals("ModifyVariable")) modifyVariable(patch, method, before);
                    else if (patch.kind().equals("ModifyExpressionValue")) expressions.add(patch);
                    else calls.add(patch);
                }
                if (!calls.isEmpty()) {
                    if (!(site instanceof MethodInsnNode call)) throw new IllegalStateException("Expected invocation at " + owner + "." + method.name);
                    invocation(calls, method, call, before);
                    method.instructions.insertBefore(site, before);
                    before = new InsnList();
                    for (PortPlan.Patch expression : expressions) expression(expression, method, Type.getReturnType(call.desc), before);
                    method.instructions.insertBefore(site, before);
                    method.instructions.insertBefore(site, after);
                    method.instructions.remove(site);
                } else {
                    method.instructions.insertBefore(site, before);
                    for (PortPlan.Patch expression : expressions) {
                        Type result;
                        if (site instanceof MethodInsnNode m && m.name.equals("<init>")) result = Type.getObjectType(m.owner);
                        else if (site instanceof MethodInsnNode m) result = Type.getReturnType(m.desc);
                        else if (site instanceof LdcInsnNode) result = Type.INT_TYPE;
                        else throw new IllegalStateException("Unsupported expression site");
                        expression(expression, method, result, after);
                    }
                    method.instructions.insert(site, after);
                }
            }
        }
        List<String> missing = patches.stream().filter(p -> !applied.contains(p.id())).map(PortPlan.Patch::label).toList();
        if (!missing.isEmpty()) {
            // Leave the class as the game has it, so the reason can be read off the bytecode later.
            try {
                java.nio.file.Path dir = java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "tenacity-runtime");
                java.nio.file.Files.createDirectories(dir);
                java.nio.file.Files.write(dir.resolve(owner.replace('/', '_') + ".class"), bytes);
            } catch (Exception ignored) {
            }
            List<String> why = new ArrayList<>();
            for (PortPlan.Patch p : patches) {
                if (applied.contains(p.id())) continue;
                List<MethodNode> targets = node.methods.stream().filter(m -> p.methods().stream().anyMatch(s -> PortPlan.methodMatches(s, m))).toList();
                // Another mod already replaced the call with its own @WrapOperation/@Redirect handler, so
                // the original invocation does not exist any more. Two mods cannot both wrap one call
                // here; skip ours instead of refusing to load at all.
                boolean taken = targets.stream().anyMatch(m -> {
                    for (AbstractInsnNode i : m.instructions)
                        if (i instanceof MethodInsnNode c && (c.name.startsWith("wrapOperation$") || c.name.startsWith("redirect$")
                                || c.name.startsWith("wrapWithCondition$"))) return true;
                    return false;
                });
                if (taken && !p.kind().equals("Inject")) {
                    System.err.println("[Tenacity] skipped hook " + p.label() + ": call already wrapped by another mod in " + owner);
                    continue;
                }
                why.add(p.label() + (targets.isEmpty() ? " (target method not found: " + p.methods() + ")" : " (no injection point)"));
            }
            if (!why.isEmpty()) throw new IllegalStateException("Unresolved runtime hooks in " + owner + ": " + why);
        }
        return Bytecode.write(node, game);
    }
    private List<AbstractInsnNode> sites(PortPlan.Patch patch, AbstractInsnNode[] instructions) {
        String kind = (String) Bytecode.value(patch.at(), "value", "");
        String target = plan.map(patch.mixin(), (String) Bytecode.value(patch.at(), "target", ""));
        List<AbstractInsnNode> found = new ArrayList<>();
        for (AbstractInsnNode i : instructions) {
            if (i.getOpcode() < 0) continue;
            if (kind.equals("HEAD")) { found.add(i); break; }
            if ((kind.equals("RETURN") || kind.equals("TAIL")) && i.getOpcode() >= IRETURN && i.getOpcode() <= RETURN) found.add(i);
            if ((kind.equals("INVOKE") || kind.equals("INVOKE_STRING")) && i instanceof MethodInsnNode m && target.equals("L" + m.owner + ";" + m.name + m.desc)) {
                if (kind.equals("INVOKE_STRING")) {
                    List<String> args = Bytecode.list(Bytecode.value(patch.at(), "args", List.of()));
                    String literal = args.stream().filter(s -> s.startsWith("ldc=")).map(s -> s.substring(4)).findFirst().orElseThrow();
                    AbstractInsnNode previous = i.getPrevious();
                    while (previous != null && previous.getOpcode() < 0) previous = previous.getPrevious();
                    if (!(previous instanceof LdcInsnNode ldc) || !literal.equals(ldc.cst)) continue;
                }
                found.add(i);
            }
            if (kind.equals("CONSTANT") && i instanceof LdcInsnNode ldc && ldc.cst instanceof Integer n
                    && Bytecode.<String>list(Bytecode.value(patch.at(), "args", List.of())).contains("intValue=" + n)) found.add(i);
            if (kind.equals("NEW") && i instanceof MethodInsnNode m && m.name.equals("<init>") && target.equals(m.desc.substring(0, m.desc.length() - 1) + "L" + m.owner + ";")) found.add(i);
        }
        if (!Set.of("HEAD", "RETURN", "TAIL", "INVOKE", "INVOKE_STRING", "NEW", "CONSTANT").contains(kind)) throw new IllegalStateException("Unsupported injection point " + kind);
        // Another mod (Meteor, motion blur, ...) may already wrap or redirect the profiler call, so the
        // literal is no longer directly followed by Profiler.swap. An Inject only needs the spot, so
        // fall back to the first invocation that consumes the literal.
        if (found.isEmpty() && kind.equals("INVOKE_STRING") && patch.kind().equals("Inject")) {
            List<String> args = Bytecode.list(Bytecode.value(patch.at(), "args", List.of()));
            String literal = args.stream().filter(s -> s.startsWith("ldc=")).map(s -> s.substring(4)).findFirst().orElse(null);
            if (literal != null) {
                for (AbstractInsnNode i : instructions) {
                    if (!(i instanceof LdcInsnNode ldc) || !literal.equals(ldc.cst)) continue;
                    AbstractInsnNode next = i.getNext();
                    for (int steps = 0; next != null && steps < 12; next = next.getNext(), steps++) {
                        if (next instanceof MethodInsnNode || next instanceof InvokeDynamicInsnNode) { found.add(next); break; }
                    }
                }
            }
            // The literal is gone entirely (another mod rewrote the method). Run at the end of the
            // method instead: later in the frame, but every local the handler captures still exists.
            if (found.isEmpty()) {
                AbstractInsnNode last = null;
                for (AbstractInsnNode i : instructions) if (i.getOpcode() >= IRETURN && i.getOpcode() <= RETURN) last = i;
                if (last != null) {
                    found.add(last);
                    System.err.println("[Tenacity] hook " + patch.label() + ": '" + literal + "' not found, using method end");
                }
            }
        }
        if (kind.equals("TAIL") && !found.isEmpty()) return List.of(found.getLast());
        int ordinal = ((Number) Bytecode.value(patch.at(), "ordinal", -1)).intValue();
        return ordinal < 0 ? found : ordinal < found.size() ? List.of(found.get(ordinal)) : List.of();
    }
    private void guard(InsnList instructions, LabelNode skip) {
        instructions.add(new FieldInsnNode(GETSTATIC, DISPATCH, "enabled", "Z"));
        instructions.add(new JumpInsnNode(IFEQ, skip));
    }
    private static int allocate(MethodNode method, Type type) {
        int slot = method.maxLocals; method.maxLocals += type.getSize(); return slot;
    }
    private void dispatch(InsnList instructions, PortPlan.Patch patch, MethodNode method, List<Argument> arguments) {
        if(arguments.size()!=Type.getArgumentTypes(patch.handler().desc).length)throw new IllegalStateException("Handler argument count mismatch in "+patch.label()+": supplied "+arguments.size()+", expected "+Type.getArgumentTypes(patch.handler().desc).length);
        Bytecode.integer(instructions, patch.id());
        if ((method.access & ACC_STATIC) != 0) instructions.add(new InsnNode(ACONST_NULL));
        else instructions.add(new VarInsnNode(ALOAD, 0));
        Bytecode.integer(instructions, arguments.size());
        instructions.add(new TypeInsnNode(ANEWARRAY, "java/lang/Object"));
        for (int i = 0; i < arguments.size(); i++) {
            instructions.add(new InsnNode(DUP)); Bytecode.integer(instructions, i);
            arguments.get(i).emit(instructions);
            instructions.add(new InsnNode(AASTORE));
        }
        instructions.add(new MethodInsnNode(INVOKESTATIC, DISPATCH, "call", "(ILjava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;", false));
    }
    /** Invocation injectors may request enclosing method arguments through MixinExtras @Local(argsOnly=true). */
    private List<Argument> invocationArguments(PortPlan.Patch patch,MethodNode method,List<Argument> operands){
        Type[] parameters=Type.getArgumentTypes(patch.handler().desc),targetArgs=Type.getArgumentTypes(method.desc);
        List<Argument> result=new ArrayList<>();int consumed=0;
        for(int p=0;p<parameters.length;p++){
            AnnotationNode capture=parameterAnnotation(patch.handler(),p,"Local");
            if(capture==null){if(consumed>=operands.size())throw new IllegalStateException("Missing invocation operand in "+patch.label());result.add(operands.get(consumed++));continue;}
            if(!Boolean.TRUE.equals(Bytecode.value(capture,"argsOnly",false)))throw new IllegalStateException("Invocation local capture requires argsOnly in "+patch.label());
            int ordinal=((Number)Bytecode.value(capture,"ordinal",-1)).intValue(),index=((Number)Bytecode.value(capture,"index",-1)).intValue();
            List<Integer> slots=new ArrayList<>();int slot=(method.access&ACC_STATIC)==0?1:0;
            for(Type arg:targetArgs){if(arg.equals(parameters[p]))slots.add(slot);slot+=arg.getSize();}
            int selected=index>=0?index:ordinal>=0&&ordinal<slots.size()?slots.get(ordinal):ordinal<0&&slots.size()==1?slots.getFirst():-1;
            if(!slots.contains(selected))throw new IllegalStateException("Ambiguous/missing captured argument in "+patch.label());
            result.add(local(parameters[p],selected));
        }
        if(consumed!=operands.size())throw new IllegalStateException("Unused invocation operands in "+patch.label());
        return result;
    }
    @FunctionalInterface interface Argument { void emit(InsnList instructions); }
    private static Argument local(Type type, int slot) {
        return instructions -> { instructions.add(new VarInsnNode(type.getOpcode(ILOAD), slot)); Bytecode.box(instructions, type); };
    }
    private void inject(PortPlan.Patch patch, MethodNode method, Frame<BasicValue> frame, boolean returns, InsnList instructions) {
        Type result = Type.getReturnType(method.desc);
        int returned = -1;
        if (returns && result.getSort() != Type.VOID) {
            returned = allocate(method, result);
            instructions.add(new VarInsnNode(result.getOpcode(ISTORE), returned));
        }
        LabelNode skip = new LabelNode(); guard(instructions, skip);
        boolean returnable = result.getSort() != Type.VOID;
        String callback = returnable ? CIR : CI;
        int ci = allocate(method, Type.getObjectType(callback));
        instructions.add(new TypeInsnNode(NEW, callback)); instructions.add(new InsnNode(DUP));
        instructions.add(new LdcInsnNode(patch.label())); instructions.add(new InsnNode(patch.cancellable() ? ICONST_1 : ICONST_0));
        if (returnable) {
            if (returned >= 0) local(result, returned).emit(instructions);
            else defaultBox(instructions, result);
        }
        instructions.add(new MethodInsnNode(INVOKESPECIAL, callback, "<init>", returnable ? "(Ljava/lang/String;ZLjava/lang/Object;)V" : "(Ljava/lang/String;Z)V", false));
        instructions.add(new VarInsnNode(ASTORE, ci));
        Type[] parameters = Type.getArgumentTypes(patch.handler().desc), targetArgs = Type.getArgumentTypes(method.desc);
        List<Argument> arguments = new ArrayList<>();
        int argIndex = 0, targetSlot = (method.access & ACC_STATIC) == 0 ? 1 : 0;
        for (int p = 0; p < parameters.length; p++) {
            Type parameter = parameters[p];
            AnnotationNode local = parameterAnnotation(patch.handler(), p, "Local");
            if (parameter.getSort() == Type.OBJECT && (parameter.getInternalName().equals(CI) || parameter.getInternalName().equals(CIR))) arguments.add(local(Type.getObjectType(callback), ci));
            else if (local != null) {
                int ordinal = ((Number) Bytecode.value(local, "ordinal", -1)).intValue();
                int explicit = ((Number) Bytecode.value(local, "index", -1)).intValue();
                List<Integer> candidates = new ArrayList<>();
                if (frame == null) throw new IllegalStateException("Unreachable local capture in " + patch.label());
                for (int l = 0; l < frame.getLocals(); l++) if (frame.getLocal(l) != null && parameter.equals(frame.getLocal(l).getType())) candidates.add(l);
                int slot;
                if (explicit >= 0) slot = explicit;
                else if (ordinal >= 0 && ordinal < candidates.size()) slot = candidates.get(ordinal);
                else if (ordinal == -1 && candidates.size() == 1) slot = candidates.getFirst();
                else throw new IllegalStateException("Ambiguous/missing local " + parameter + " in " + patch.label() + ": " + candidates);
                arguments.add(local(parameter, slot));
            } else {
                if (argIndex >= targetArgs.length || !parameter.equals(targetArgs[argIndex])) throw new IllegalStateException("Handler arguments differ for " + patch.label());
                arguments.add(local(parameter, targetSlot)); targetSlot += parameter.getSize(); argIndex++;
            }
        }
        dispatch(instructions, patch, method, arguments); instructions.add(new InsnNode(POP));
        if (patch.cancellable() || returns && returnable) {
            if (!returns) {
                instructions.add(new VarInsnNode(ALOAD, ci));
                instructions.add(new MethodInsnNode(INVOKEVIRTUAL, CI, "isCancelled", "()Z", false));
                instructions.add(new JumpInsnNode(IFEQ, skip));
            }
            if (returnable) {
                instructions.add(new VarInsnNode(ALOAD, ci));
                instructions.add(new MethodInsnNode(INVOKEVIRTUAL, CIR, "getReturnValue", "()Ljava/lang/Object;", false));
                Bytecode.unbox(instructions, result);
                if (returns) instructions.add(new VarInsnNode(result.getOpcode(ISTORE), returned));
            }
            if (!returns) instructions.add(new InsnNode(result.getOpcode(IRETURN)));
        }
        instructions.add(skip);
        if (returned >= 0) instructions.add(new VarInsnNode(result.getOpcode(ILOAD), returned));
    }
    private static AnnotationNode parameterAnnotation(MethodNode method, int parameter, String name) {
        AnnotationNode result = method.invisibleParameterAnnotations == null ? null : Bytecode.annotation(method.invisibleParameterAnnotations[parameter], name);
        return result != null || method.visibleParameterAnnotations == null ? result : Bytecode.annotation(method.visibleParameterAnnotations[parameter], name);
    }
    private static void defaultBox(InsnList instructions, Type type) {
        switch (type.getSort()) {
            case Type.LONG -> instructions.add(new InsnNode(LCONST_0)); case Type.FLOAT -> instructions.add(new InsnNode(FCONST_0));
            case Type.DOUBLE -> instructions.add(new InsnNode(DCONST_0));
            case Type.OBJECT, Type.ARRAY -> instructions.add(new InsnNode(ACONST_NULL));
            default -> instructions.add(new InsnNode(ICONST_0));
        }
        Bytecode.box(instructions, type);
    }
    private void modifyVariable(PortPlan.Patch patch, MethodNode method, InsnList instructions) {
        Type type = Type.getArgumentTypes(patch.handler().desc)[0];
        Type[] args = Type.getArgumentTypes(method.desc);
        int slot = (method.access & ACC_STATIC) == 0 ? 1 : 0, found = -1;
        AnnotationNode annotation=Bytecode.annotation(patch.handler().visibleAnnotations,"ModifyVariable");
        if(annotation==null)annotation=Bytecode.annotation(patch.handler().invisibleAnnotations,"ModifyVariable");
        int ordinal=((Number)Bytecode.value(annotation,"ordinal",-1)).intValue(),index=((Number)Bytecode.value(annotation,"index",-1)).intValue(),matched=0;
        for (Type argument : args) {
            if(argument.equals(type)){
                boolean selected=(index<0||index==slot)&&(ordinal<0||ordinal==matched);
                if(selected){if(found>=0)throw new IllegalStateException("Ambiguous argument for "+patch.label());found=slot;}
                matched++;
            }
            slot+=argument.getSize();
        }
        if (found < 0) throw new IllegalStateException("Missing argument for " + patch.label());
        LabelNode skip = new LabelNode(); guard(instructions, skip);
        dispatch(instructions, patch, method, List.of(local(type, found)));
        Bytecode.unbox(instructions, type); instructions.add(new VarInsnNode(type.getOpcode(ISTORE), found)); instructions.add(skip);
    }
    private void expression(PortPlan.Patch patch, MethodNode method, Type result, InsnList instructions) {
        int slot = allocate(method, result);
        instructions.add(new VarInsnNode(result.getOpcode(ISTORE), slot));
        LabelNode skip = new LabelNode(); guard(instructions, skip);
        dispatch(instructions, patch, method, List.of(local(result, slot))); Bytecode.unbox(instructions, result);
        instructions.add(new VarInsnNode(result.getOpcode(ISTORE), slot)); instructions.add(skip);
        instructions.add(new VarInsnNode(result.getOpcode(ILOAD), slot));
    }
    private void invocation(List<PortPlan.Patch> patches, MethodNode method, MethodInsnNode call, InsnList instructions) {
        List<Type> operands = new ArrayList<>();
        if (call.getOpcode() != INVOKESTATIC) operands.add(Type.getObjectType(call.owner));
        operands.addAll(Arrays.asList(Type.getArgumentTypes(call.desc)));
        int[] slots = new int[operands.size()];
        for (int i = operands.size() - 1; i >= 0; i--) {
            slots[i] = allocate(method, operands.get(i));
            instructions.add(new VarInsnNode(operands.get(i).getOpcode(ISTORE), slots[i]));
        }
        LabelNode vanilla = new LabelNode(), done = new LabelNode(); guard(instructions, vanilla);
        List<PortPlan.Patch> replacing = patches.stream().filter(p -> !p.kind().equals("ModifyArg")).toList();
        for (PortPlan.Patch patch : patches) if (patch.kind().equals("ModifyArg")) {
            int index = patch.argument();
            Type handlerArg = Type.getArgumentTypes(patch.handler().desc)[0];
            int receiver = call.getOpcode() == INVOKESTATIC ? 0 : 1;
            if (index < 0) {
                for (int i = receiver; i < operands.size(); i++) if (operands.get(i).equals(handlerArg)) {
                    if (index >= 0) throw new IllegalStateException("Ambiguous ModifyArg " + patch.label()); index = i - receiver;
                }
            }
            if (index < 0 || index + receiver >= operands.size()) throw new IllegalStateException("Missing ModifyArg " + patch.label());
            dispatch(instructions, patch, method, List.of(local(handlerArg, slots[index + receiver])));
            Bytecode.unbox(instructions, handlerArg); instructions.add(new VarInsnNode(handlerArg.getOpcode(ISTORE), slots[index + receiver]));
        }
        if (replacing.size() > 1) throw new IllegalStateException("Overlapping redirects/wrappers at " + call.owner + "." + call.name);
        if (replacing.isEmpty()) emitOriginal(instructions, call, operands, slots);
        else {
            PortPlan.Patch patch = replacing.getFirst();
            List<Argument> arguments = new ArrayList<>();
            for (int i = 0; i < operands.size(); i++) arguments.add(local(operands.get(i), slots[i]));
            if (patch.kind().equals("WrapOperation")) arguments.add(out -> {
                out.add(new LdcInsnNode(call.owner)); out.add(new LdcInsnNode(call.name)); out.add(new LdcInsnNode(call.desc));
                Bytecode.integer(out, call.getOpcode()); out.add(new LdcInsnNode(patch.owner()));
                out.add(new MethodInsnNode(INVOKESTATIC, DISPATCH, "operation", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;ILjava/lang/String;)Lcom/llamalad7/mixinextras/injector/wrapoperation/Operation;", false));
            });
            dispatch(instructions, patch, method, invocationArguments(patch,method,arguments));
            if (patch.kind().equals("WrapWithCondition")) {
                Bytecode.unbox(instructions, Type.BOOLEAN_TYPE);
                instructions.add(new JumpInsnNode(IFEQ, done)); emitOriginal(instructions, call, operands, slots);
            } else Bytecode.unbox(instructions, Type.getReturnType(call.desc));
        }
        instructions.add(new JumpInsnNode(GOTO, done));
        instructions.add(vanilla); emitOriginal(instructions, call, operands, slots); instructions.add(done);
    }
    private static void emitOriginal(InsnList instructions, MethodInsnNode call, List<Type> operands, int[] slots) {
        for (int i = 0; i < slots.length; i++) instructions.add(new VarInsnNode(operands.get(i).getOpcode(ILOAD), slots[i]));
        instructions.add(new MethodInsnNode(call.getOpcode(), call.owner, call.name, call.desc, call.itf));
    }
    @Override public byte[] transform(Module module, ClassLoader loader, String name, Class<?> redefining, ProtectionDomain domain, byte[] bytes) {
        if (loader != game || !plan.patches.containsKey(name)) return null;
        try { return apply(name, bytes); }
        catch (Throwable failure) {
            RuntimePort.fail("Cannot transform " + name, failure);
            throw new ClassFormatError("Tenacity runtime hook failed for " + name);
        }
    }
}
