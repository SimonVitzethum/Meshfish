struct MeshfishFrameUniforms
{
    float4x4 viewProjection;
    float4 frustumPlanes[6];
    float4 cameraPosition;
    uint frustumCullingEnabled;
};

struct MeshVertex
{
    float4 position : SV_Position;

    [[vk::location(0)]]
    float4 color;

    [[vk::location(1)]]
    float2 uv;
};

static const uint MESHFISH_SCENE_HEADER_SIZE = 16u;
static const uint MESHFISH_MAX_SCENE_BLOCKS = 5400u;
static const uint MESHFISH_MAX_SCENE_MODELS = 4096u;
static const uint MESHFISH_MAX_SCENE_QUADS = 16384u;
static const uint MESHFISH_SCENE_BLOCK_RECORD_SIZE = 32u;
static const uint MESHFISH_SCENE_MODEL_RECORD_SIZE = 8u;
static const uint MESHFISH_SCENE_QUAD_RECORD_SIZE = 96u;
static const uint MESHFISH_SCENE_PRIMITIVE_RECORD_SIZE = 8u;
static const uint MESHFISH_SCENE_BLOCKS_OFFSET = MESHFISH_SCENE_HEADER_SIZE;
static const uint MESHFISH_SCENE_MODELS_OFFSET = MESHFISH_SCENE_BLOCKS_OFFSET + MESHFISH_MAX_SCENE_BLOCKS * MESHFISH_SCENE_BLOCK_RECORD_SIZE;
static const uint MESHFISH_SCENE_QUADS_OFFSET = MESHFISH_SCENE_MODELS_OFFSET + MESHFISH_MAX_SCENE_MODELS * MESHFISH_SCENE_MODEL_RECORD_SIZE;
static const uint MESHFISH_SCENE_PRIMITIVES_OFFSET = MESHFISH_SCENE_QUADS_OFFSET + MESHFISH_MAX_SCENE_QUADS * MESHFISH_SCENE_QUAD_RECORD_SIZE;

[[vk::binding(1, 0)]]
ConstantBuffer<MeshfishFrameUniforms> meshfishFrame;

[[vk::binding(0, 0)]]
StructuredBuffer<uint> meshfishScene;

uint loadSceneUint(uint byteOffset)
{
    return meshfishScene[byteOffset >> 2u];
}

float loadSceneFloat(uint byteOffset)
{
    return asfloat(loadSceneUint(byteOffset));
}

uint readSceneBlockCount()
{
    return loadSceneUint(0u);
}

uint readScenePrimitiveCount()
{
    return loadSceneUint(12u);
}

uint readScenePrimitiveBlock(uint primitiveIndex)
{
    return loadSceneUint(MESHFISH_SCENE_PRIMITIVES_OFFSET + primitiveIndex * MESHFISH_SCENE_PRIMITIVE_RECORD_SIZE);
}

uint readScenePrimitiveQuad(uint primitiveIndex)
{
    return loadSceneUint(MESHFISH_SCENE_PRIMITIVES_OFFSET + primitiveIndex * MESHFISH_SCENE_PRIMITIVE_RECORD_SIZE + 4u);
}

uint readSceneBlockModel(uint blockIndex)
{
    return loadSceneUint(MESHFISH_SCENE_BLOCKS_OFFSET + blockIndex * MESHFISH_SCENE_BLOCK_RECORD_SIZE + 12u);
}

float3 readSceneBlockPosition(uint blockIndex)
{
    uint byteOffset = MESHFISH_SCENE_BLOCKS_OFFSET + blockIndex * MESHFISH_SCENE_BLOCK_RECORD_SIZE;
    return float3(
        loadSceneFloat(byteOffset + 0u),
        loadSceneFloat(byteOffset + 4u),
        loadSceneFloat(byteOffset + 8u));
}

float4 readSceneBlockColor(uint blockIndex)
{
    uint packed = loadSceneUint(MESHFISH_SCENE_BLOCKS_OFFSET + blockIndex * MESHFISH_SCENE_BLOCK_RECORD_SIZE + 20u);
    return float4(
        float(packed & 0xffu),
        float((packed >> 8u) & 0xffu),
        float((packed >> 16u) & 0xffu),
        float((packed >> 24u) & 0xffu)) / 255.0f;
}

uint readSceneModelFirstQuad(uint modelIndex)
{
    return loadSceneUint(MESHFISH_SCENE_MODELS_OFFSET + modelIndex * MESHFISH_SCENE_MODEL_RECORD_SIZE);
}

uint readSceneModelQuadCount(uint modelIndex)
{
    return loadSceneUint(MESHFISH_SCENE_MODELS_OFFSET + modelIndex * MESHFISH_SCENE_MODEL_RECORD_SIZE + 4u);
}

uint sceneQuadOffset(uint quadIndex)
{
    return MESHFISH_SCENE_QUADS_OFFSET + quadIndex * MESHFISH_SCENE_QUAD_RECORD_SIZE;
}

float3 readSceneQuadPosition(uint quadIndex, uint vertexIndex)
{
    uint byteOffset = sceneQuadOffset(quadIndex) + vertexIndex * 20u;
    return float3(
        loadSceneFloat(byteOffset + 0u),
        loadSceneFloat(byteOffset + 4u),
        loadSceneFloat(byteOffset + 8u));
}

float2 readSceneQuadUv(uint quadIndex, uint vertexIndex)
{
    uint byteOffset = sceneQuadOffset(quadIndex) + vertexIndex * 20u + 12u;
    return float2(
        loadSceneFloat(byteOffset + 0u),
        loadSceneFloat(byteOffset + 4u));
}

float4 projectPosition(float3 position)
{
    float3 localPosition = position - meshfishFrame.cameraPosition.xyz;
    return mul(meshfishFrame.viewProjection, float4(localPosition, 1.0f));
}

[Shader("mesh")]
[outputtopology("triangle")]
[numthreads(1, 1, 1)]
void main(
    uint3 groupId : SV_GroupID,
    OutputVertices<MeshVertex, 4> vertices,
    OutputIndices<uint3, 2> triangles)
{
    if (groupId.x == 0u && readScenePrimitiveCount() > 0u)
    {
        SetMeshOutputCounts(3, 1);
        vertices[0].position = float4(-1.0f, -1.0f, 0.0f, 1.0f);
        vertices[0].color = float4(1.0f, 0.0f, 1.0f, 1.0f);
        vertices[0].uv = float2(0.0f, 0.0f);
        vertices[1].position = float4(3.0f, -1.0f, 0.0f, 1.0f);
        vertices[1].color = float4(1.0f, 0.0f, 1.0f, 1.0f);
        vertices[1].uv = float2(1.0f, 0.0f);
        vertices[2].position = float4(-1.0f, 3.0f, 0.0f, 1.0f);
        vertices[2].color = float4(1.0f, 0.0f, 1.0f, 1.0f);
        vertices[2].uv = float2(0.0f, 1.0f);
        triangles[0] = uint3(0u, 1u, 2u);
        return;
    }

    uint primitiveIndex = groupId.x;
    if (primitiveIndex >= readScenePrimitiveCount())
    {
        SetMeshOutputCounts(0, 0);
        return;
    }

    uint blockIndex = readScenePrimitiveBlock(primitiveIndex);
    uint quadIndex = readScenePrimitiveQuad(primitiveIndex);
    if (blockIndex >= readSceneBlockCount())
    {
        SetMeshOutputCounts(0, 0);
        return;
    }

    uint modelIndex = readSceneBlockModel(blockIndex);
    uint firstQuad = readSceneModelFirstQuad(modelIndex);
    uint quadCount = readSceneModelQuadCount(modelIndex);
    if (quadIndex < firstQuad || quadIndex >= firstQuad + quadCount)
    {
        SetMeshOutputCounts(0, 0);
        return;
    }

    SetMeshOutputCounts(4, 2);
    float3 blockPosition = readSceneBlockPosition(blockIndex);
    float4 color = readSceneBlockColor(blockIndex);
    for (uint i = 0u; i < 4u; i++)
    {
        vertices[i].position = projectPosition(blockPosition + readSceneQuadPosition(quadIndex, i));
        vertices[i].color = color;
        vertices[i].uv = readSceneQuadUv(quadIndex, i);
    }

    triangles[0] = uint3(0u, 1u, 2u);
    triangles[1] = uint3(0u, 2u, 3u);
}
