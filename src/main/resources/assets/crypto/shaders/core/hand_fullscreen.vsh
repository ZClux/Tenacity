#version 330
out vec2 TexCoord;
void main() {
    vec2 p[6]=vec2[](vec2(-1,-1),vec2(1,-1),vec2(1,1),vec2(-1,-1),vec2(1,1),vec2(-1,1));
    vec2 v=p[gl_VertexID]; TexCoord=v*.5+.5; gl_Position=vec4(v,0,1);
}
