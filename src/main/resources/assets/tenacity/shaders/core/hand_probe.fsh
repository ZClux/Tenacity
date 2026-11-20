#version 330
in vec2 TexCoord;
uniform sampler2D Sampler0;
out vec4 OutColor;
void main() { OutColor=texture(Sampler0,TexCoord); }
