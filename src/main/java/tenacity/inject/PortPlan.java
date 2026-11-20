package tenacity.inject;

import com.google.gson.*;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.jar.JarFile;

/** The existing Mixin declarations are the port's source of truth; no module hook is silently omitted. */
final class PortPlan {
    static final String[] CONFIGS = {"tenacity.mixins.json"};
    final Map<String, ClassNode> mixins = new LinkedHashMap<>();
    final Map<String, String> targets = new LinkedHashMap<>();
    final Map<String, List<Patch>> patches = new LinkedHashMap<>();
    final Map<String, Map<String, Member>> accessors = new LinkedHashMap<>();
    final Set<String> widened = new HashSet<>();
    final JsonObject refmap;
    final List<Patch> all = new ArrayList<>();

    record Member(String owner, String name, String desc, boolean field, boolean setter) {}
    record Patch(int id, String mixin, MethodNode handler, String owner, String kind,
                 List<String> methods, AnnotationNode at, boolean cancellable, int argument) {
        String label() { return mixin + "." + handler.name; }
    }

    PortPlan(JarFile jar) throws IOException {
        var ref = jar.stream().filter(e -> e.getName().endsWith("-refmap.json")).findFirst();
        refmap = ref.isPresent() ? JsonParser.parseString(new String(jar.getInputStream(ref.get()).readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("mappings") : new JsonObject();
        for (String config : CONFIGS) {
            JsonObject json = JsonParser.parseString(new String(jar.getInputStream(jar.getJarEntry(config)).readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            String pkg = json.get("package").getAsString().replace('.', '/');
            for (JsonElement entry : json.getAsJsonArray("client")) {
                String name = pkg + "/" + entry.getAsString().replace('.', '/');
                ClassNode node = Bytecode.read(jar.getInputStream(jar.getJarEntry(name + ".class")).readAllBytes());
                AnnotationNode mixin = Bytecode.annotation(node.invisibleAnnotations, "Mixin");
                if (mixin == null) mixin = Bytecode.annotation(node.visibleAnnotations, "Mixin");
                List<Type> owners = Bytecode.list(Bytecode.value(mixin, "value", List.of()));
                if (owners.isEmpty()) {
                    List<String> names = Bytecode.list(Bytecode.value(mixin, "targets", List.of()));
                    owners = names.stream().map(t -> Type.getObjectType(map(name, t).replace('.', '/'))).toList();
                }
                if (owners.size() != 1) throw new IllegalStateException("Expected one target for " + name);
                mixins.put(name, node);
                targets.put(name, owners.getFirst().getInternalName());
            }
        }
        List<String> ordered = new ArrayList<>(mixins.keySet());
        ordered.sort(Comparator.comparingInt(name -> {
            AnnotationNode a = Bytecode.annotation(mixins.get(name).invisibleAnnotations, "Mixin");
            return ((Number) Bytecode.value(a, "priority", 1000)).intValue();
        }));
        for (String name : ordered) {
            ClassNode node = mixins.get(name);
            String owner = targets.get(name);
            for (MethodNode method : node.methods) {
                List<AnnotationNode> annotations = new ArrayList<>();
                if (method.visibleAnnotations != null) annotations.addAll(method.visibleAnnotations);
                if (method.invisibleAnnotations != null) annotations.addAll(method.invisibleAnnotations);
                for (AnnotationNode annotation : annotations) {
                    String kind = Type.getType(annotation.desc).getClassName();
                    kind = kind.substring(kind.lastIndexOf('.') + 1);
                    if (kind.equals("Accessor") || kind.equals("Invoker")) {
                        String original = (String) Bytecode.value(annotation, "value", "");
                        String mapped = map(name, original);
                        String member = mapped;
                        int semi = member.startsWith("L") ? member.indexOf(';') : -1;
                        if (semi >= 0) member = member.substring(semi + 1);
                        int end = kind.equals("Accessor") ? member.indexOf(':') : member.indexOf('(');
                        if (end >= 0) member = member.substring(0, end);
                        Type[] args = Type.getArgumentTypes(method.desc);
                        boolean setter = kind.equals("Accessor") && args.length == 1;
                        String desc = kind.equals("Accessor") ? (setter ? args[0] : Type.getReturnType(method.desc)).getDescriptor() : method.desc;
                        accessors.computeIfAbsent(name, k -> new HashMap<>()).put(method.name + method.desc, new Member(owner, member, desc, kind.equals("Accessor"), setter));
                        continue;
                    }
                    if (!Set.of("Inject", "Redirect", "ModifyExpressionValue", "ModifyArg", "ModifyVariable", "WrapOperation", "WrapWithCondition", "ModifyConstant").contains(kind)) {
                        if (annotation.desc.contains("/injection/") || annotation.desc.contains("/injector/"))
                            throw new IllegalStateException("Unported injector " + kind + " in " + name);
                        continue;
                    }
                    List<String> methods = Bytecode.<String>list(Bytecode.value(annotation, "method", List.of())).stream().map(m -> map(name, m)).toList();
                    if (kind.equals("ModifyConstant")) {
                        // int constants only: treated as an expression modifier on the matching LDC
                        AnnotationNode constant = Bytecode.<AnnotationNode>list(Bytecode.value(annotation, "constant", List.of())).get(0);
                        Object iv = Bytecode.value(constant, "intValue", null);
                        if (iv == null) throw new IllegalStateException("Unported ModifyConstant (only intValue supported) in " + name);
                        AnnotationNode at = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");
                        at.values = new ArrayList<>(List.of("value", "CONSTANT", "args", List.of("intValue=" + iv)));
                        Patch patch = new Patch(all.size(), name, method, owner, "ModifyExpressionValue", methods, at, false, -1);
                        all.add(patch);
                        patches.computeIfAbsent(owner, k -> new ArrayList<>()).add(patch);
                        continue;
                    }
                    for (AnnotationNode at : Bytecode.<AnnotationNode>list(Bytecode.value(annotation, "at", List.of()))) {
                        Patch patch = new Patch(all.size(), name, method, owner, kind, methods, at,
                                (boolean) Bytecode.value(annotation, "cancellable", false), ((Number) Bytecode.value(annotation, "index", -1)).intValue());
                        all.add(patch);
                        patches.computeIfAbsent(owner, k -> new ArrayList<>()).add(patch);
                    }
                }
            }
        }
        if (all.isEmpty()) throw new IllegalStateException("No hooks found in injectable jar");
    }
    String map(String mixin, String selector) {
        JsonObject entries = refmap.has(mixin) ? refmap.getAsJsonObject(mixin) : null;
        return entries != null && entries.has(selector) ? entries.get(selector).getAsString() : selector;
    }
    static boolean methodMatches(String selector, MethodNode method) {
        if (selector.startsWith("L")) selector = selector.substring(selector.indexOf(';') + 1);
        int paren = selector.indexOf('(');
        return paren < 0 ? selector.equals(method.name) : selector.equals(method.name + method.desc);
    }
}

