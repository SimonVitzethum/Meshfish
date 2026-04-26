struct MeshPayload
{
    float4 color;
};

struct MeshVertex
{
    float4 position : SV_Position;
    float4 color : COLOR0;
};

[numthreads(1, 1, 1)]
[outputtopology("triangle")]
void main(
    uint groupIndex : SV_GroupIndex,
    in payload MeshPayload payload,
    out vertices MeshVertex vertices[3],
    out indices uint3 triangles[1]
)
{
    if (groupIndex != 0)
    {
        return;
    }

    SetMeshOutputCounts(3, 1);

    vertices[0].position = float4(-0.55, -0.35, 0.0, 1.0);
    vertices[0].color = payload.color;

    vertices[1].position = float4(0.55, -0.35, 0.0, 1.0);
    vertices[1].color = float4(payload.color.rgb * 0.75, 1.0);

    vertices[2].position = float4(0.0, 0.55, 0.0, 1.0);
    vertices[2].color = float4(1.0, 0.45, 0.20, 1.0);

    triangles[0] = uint3(0, 1, 2);
}
