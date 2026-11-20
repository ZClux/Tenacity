#version 330
in vec2 TexCoord;
uniform sampler2D BeforeSampler;
uniform sampler2D AfterSampler;
uniform sampler2D DepthBeforeSampler;
uniform sampler2D DepthAfterSampler;
out vec4 OutColor;
void main() {
    vec4 after=texture(AfterSampler,TexCoord);
    vec3 delta=abs(after.rgb-texture(BeforeSampler,TexCoord).rgb);
    float depth=texture(DepthBeforeSampler,TexCoord).r-texture(DepthAfterSampler,TexCoord).r;
    // Soft mask instead of 0/1: pixels whose colour is close to the world behind the hand no longer pop in and out frame to frame
    // while the view turns, which read as the effect stuttering along the hand's edge.
    float byDepth=clamp(depth*400.,0.,1.);
    float byColour=smoothstep(.006,.03,max(max(delta.r,delta.g),delta.b));
    OutColor=vec4(after.rgb,max(byDepth,byColour));
}
