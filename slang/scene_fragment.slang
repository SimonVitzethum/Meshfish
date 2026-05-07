struct FragmentInput
{
    [[vk::location(0)]]
    float4 color;

    [[vk::location(1)]]
    float2 uv;
};

[[vk::binding(5, 0)]]
Sampler2D meshfishBlockAtlas;

float3 applyOutputTransfer(float3 color)
{
    float3 clamped = saturate(color);
    return pow(clamped, float3(1.0f / 2.2f));
}

[Shader("fragment")]
float4 main(FragmentInput input) : SV_Target0
{
    float4 texel = meshfishBlockAtlas.SampleLevel(input.uv, 0.0f);
    float4 color = texel * input.color;
    if (color.a <= 0.01f)
    {
        discard;
    }

    return float4(applyOutputTransfer(color.rgb), color.a);
}
