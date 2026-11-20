#version 330
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:globals.glsl>
#moj_import <minecraft:projection.glsl>
in vec3 Position;
in vec2 UV0;
out vec2 TexCoord;
out vec2 PatternCoord;
void main(){
    TexCoord=UV0;
    // Entity vertices are camera relative, so the exact camera position is CameraBlockPos - CameraOffset (the same identity terrain.vsh
    // uses). Adding it back gives one world-fixed coordinate shared by every part, layer and armor piece of a player, so the pattern runs
    // across the whole body without the per-part gaps of skin UVs and does not follow the camera when it turns. The block position is
    // wrapped to keep float precision far from the origin.
    vec3 world=Position+vec3(CameraBlockPos&ivec3(2047))-CameraOffset;
    // A skew projection: unlike dropping an axis it keeps both pattern axes varying on front, side and top faces alike.
    PatternCoord=vec2(world.x+0.61*world.z,world.y+0.83*world.z)*0.6;
    gl_Position=ProjMat*ModelViewMat*vec4(Position,1.0);
}
