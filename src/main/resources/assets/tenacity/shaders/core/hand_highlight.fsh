#version 330
in vec2 TexCoord;
uniform sampler2D Sampler0;
layout(std140) uniform HandData { vec4 timeAlpha;vec4 accent;vec4 center; };
out vec4 OutColor;
void main() { OutColor=vec4(accent.rgb,texture(Sampler0,TexCoord).a*.4*timeAlpha.y); }
