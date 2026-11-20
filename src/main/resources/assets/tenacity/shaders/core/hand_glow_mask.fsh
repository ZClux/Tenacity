#version 330
in vec2 TexCoord;
uniform sampler2D Sampler0;
out vec4 OutColor;
void main(){float m=texture(Sampler0,TexCoord).a;OutColor=vec4(m);}
