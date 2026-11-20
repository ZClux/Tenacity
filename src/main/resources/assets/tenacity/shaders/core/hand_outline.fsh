#version 330
in vec2 TexCoord;
uniform sampler2D Sampler0;
layout(std140) uniform HandData {vec4 timeAlpha;vec4 accent;vec4 center;};
out vec4 OutColor;
void main(){
    vec2 texel=timeAlpha.zw;
    float inside=texture(Sampler0,TexCoord).a;
    float border=0.,halo=0.;
    for(int i=0;i<16;i++){
        float angle=float(i)*6.28318530718/16.;vec2 dir=vec2(cos(angle),sin(angle));
        border=max(border,texture(Sampler0,TexCoord+dir*texel*center.z).a);
        halo+=texture(Sampler0,TexCoord+dir*texel*(center.z+4.)).a/16.;
    }
    float edge=max(0.,border-inside);
    float alpha=(edge+halo*(1.-inside)*center.w*.6)*timeAlpha.y;
    OutColor=vec4(accent.rgb,clamp(alpha,0.,1.));
}
