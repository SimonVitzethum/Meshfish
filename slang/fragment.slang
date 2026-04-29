struct FragmentInput
{
    [[Location(0)]]
    float4 color;
};

[Shader("fragment")]
float4 main(FragmentInput input) : SV_Target0
{
    return input.color;
}