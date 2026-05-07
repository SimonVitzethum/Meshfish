struct MeshPayload
{
    uint groupOffset;
    uint drawFlags;
};

struct MeshfishFrameUniforms
{
    float4x4 viewProjection;
    float4 frustumPlanes[6];
    float4 cameraPosition;
    uint frustumCullingEnabled;
};

struct MeshfishDrawCommand
{
    uint baseVertex;
    uint firstIndex;
    uint indexCount;
    uint instanceCount;
    uint firstVertex;
    uint vertexCount;
    uint indexType;
    uint flags;
    uint vertexStride;
    uint positionOffset;
    uint colorOffset;
    uint uvOffset;
    uint transformMode;
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
static const uint MESHFISH_MAX_MODEL_QUADS = 64u;
static const uint MESHFISH_MAX_SCENE_QUADS = 16384u;
static const uint MESHFISH_SCENE_BLOCK_RECORD_SIZE = 32u;
static const uint MESHFISH_SCENE_MODEL_RECORD_SIZE = 8u;
static const uint MESHFISH_SCENE_QUAD_RECORD_SIZE = 96u;
static const uint MESHFISH_SCENE_PRIMITIVE_RECORD_SIZE = 8u;
static const uint MESHFISH_SCENE_BLOCKS_OFFSET = MESHFISH_SCENE_HEADER_SIZE;
static const uint MESHFISH_SCENE_MODELS_OFFSET = MESHFISH_SCENE_BLOCKS_OFFSET + MESHFISH_MAX_SCENE_BLOCKS * MESHFISH_SCENE_BLOCK_RECORD_SIZE;
static const uint MESHFISH_SCENE_QUADS_OFFSET = MESHFISH_SCENE_MODELS_OFFSET + MESHFISH_MAX_SCENE_MODELS * MESHFISH_SCENE_MODEL_RECORD_SIZE;
static const uint MESHFISH_SCENE_PRIMITIVES_OFFSET = MESHFISH_SCENE_QUADS_OFFSET + MESHFISH_MAX_SCENE_QUADS * MESHFISH_SCENE_QUAD_RECORD_SIZE;

[[vk::binding(1, 1)]]
ConstantBuffer<MeshfishFrameUniforms> meshfishFrame;

[[vk::binding(2, 1)]]
StructuredBuffer<MeshfishDrawCommand> meshfishDraws;

[[vk::binding(3, 1)]]
ByteAddressBuffer meshfishVertices;

[[vk::binding(4, 1)]]
ByteAddressBuffer meshfishIndices;

[[vk::binding(0, 1)]]
ByteAddressBuffer meshfishScene;

float readFloat(uint byteOffset)
{
    return asfloat(meshfishVertices.Load(byteOffset));
}

float3 readPosition(uint vertexIndex, MeshfishDrawCommand draw)
{
    uint byteOffset = vertexIndex * draw.vertexStride + draw.positionOffset;
    return float3(
        readFloat(byteOffset),
        readFloat(byteOffset + 4u),
        readFloat(byteOffset + 8u));
}

uint readIndex(uint indexNumber, MeshfishDrawCommand draw)
{
    uint byteOffset = (draw.firstIndex + indexNumber) * (draw.indexType == 1u ? 2u : 4u);
    if (draw.indexType == 1u)
    {
        uint packed = meshfishIndices.Load(byteOffset & ~3u);
        return ((byteOffset & 2u) == 0u) ? (packed & 0xffffu) : ((packed >> 16u) & 0xffffu);
    }

    return meshfishIndices.Load(byteOffset);
}

float4 readColor(uint vertexIndex, MeshfishDrawCommand draw)
{
    if (draw.colorOffset == 0xffffffffu)
    {
        return float4(1.0f, 1.0f, 1.0f, 1.0f);
    }

    uint packed = meshfishVertices.Load(vertexIndex * draw.vertexStride + draw.colorOffset);
    return float4(
        float(packed & 0xffu),
        float((packed >> 8u) & 0xffu),
        float((packed >> 16u) & 0xffu),
        float((packed >> 24u) & 0xffu)) / 255.0f;
}

float2 readUv(uint vertexIndex, MeshfishDrawCommand draw)
{
    if (draw.uvOffset == 0xffffffffu)
    {
        return float2(0.0f, 0.0f);
    }

    uint byteOffset = vertexIndex * draw.vertexStride + draw.uvOffset;
    return float2(
        readFloat(byteOffset),
        readFloat(byteOffset + 4u));
}

float4 projectPosition(float3 position, MeshfishDrawCommand draw)
{
    float3 localPosition = position;
    if (draw.transformMode == 1u || draw.transformMode == 2u)
    {
        localPosition -= meshfishFrame.cameraPosition.xyz;
    }

    return mul(meshfishFrame.viewProjection, float4(localPosition, 1.0f));
}

uint scenePrimitiveCount(MeshfishDrawCommand draw)
{
    return readScenePrimitiveCount();
}

uint readSceneBlockCount()
{
    return meshfishScene.Load(0u);
}

uint readScenePrimitiveCount()
{
    return meshfishScene.Load(12u);
}

uint readScenePrimitiveBlock(uint primitiveIndex)
{
    return meshfishScene.Load(MESHFISH_SCENE_PRIMITIVES_OFFSET + primitiveIndex * MESHFISH_SCENE_PRIMITIVE_RECORD_SIZE);
}

uint readScenePrimitiveQuad(uint primitiveIndex)
{
    return meshfishScene.Load(MESHFISH_SCENE_PRIMITIVES_OFFSET + primitiveIndex * MESHFISH_SCENE_PRIMITIVE_RECORD_SIZE + 4u);
}

uint readSceneBlockModel(uint blockIndex)
{
    return meshfishScene.Load(MESHFISH_SCENE_BLOCKS_OFFSET + blockIndex * MESHFISH_SCENE_BLOCK_RECORD_SIZE + 12u);
}

uint readSceneBlockVisibleMask(uint blockIndex)
{
    return meshfishScene.Load(MESHFISH_SCENE_BLOCKS_OFFSET + blockIndex * MESHFISH_SCENE_BLOCK_RECORD_SIZE + 16u);
}

float3 readSceneBlockPosition(uint blockIndex)
{
    uint byteOffset = MESHFISH_SCENE_BLOCKS_OFFSET + blockIndex * MESHFISH_SCENE_BLOCK_RECORD_SIZE;
    return float3(
        asfloat(meshfishScene.Load(byteOffset + 0u)),
        asfloat(meshfishScene.Load(byteOffset + 4u)),
        asfloat(meshfishScene.Load(byteOffset + 8u)));
}

float4 readSceneBlockColor(uint blockIndex)
{
    uint packed = meshfishScene.Load(MESHFISH_SCENE_BLOCKS_OFFSET + blockIndex * MESHFISH_SCENE_BLOCK_RECORD_SIZE + 20u);
    return float4(
        float(packed & 0xffu),
        float((packed >> 8u) & 0xffu),
        float((packed >> 16u) & 0xffu),
        float((packed >> 24u) & 0xffu)) / 255.0f;
}

uint readSceneModelFirstQuad(uint modelIndex)
{
    return meshfishScene.Load(MESHFISH_SCENE_MODELS_OFFSET + modelIndex * MESHFISH_SCENE_MODEL_RECORD_SIZE);
}

uint readSceneModelQuadCount(uint modelIndex)
{
    return meshfishScene.Load(MESHFISH_SCENE_MODELS_OFFSET + modelIndex * MESHFISH_SCENE_MODEL_RECORD_SIZE + 4u);
}

uint sceneQuadOffset(uint quadIndex)
{
    return MESHFISH_SCENE_QUADS_OFFSET + quadIndex * MESHFISH_SCENE_QUAD_RECORD_SIZE;
}

float3 readSceneQuadPosition(uint quadIndex, uint vertexIndex)
{
    uint byteOffset = sceneQuadOffset(quadIndex) + vertexIndex * 20u;
    return float3(
        asfloat(meshfishScene.Load(byteOffset + 0u)),
        asfloat(meshfishScene.Load(byteOffset + 4u)),
        asfloat(meshfishScene.Load(byteOffset + 8u)));
}

float2 readSceneQuadUv(uint quadIndex, uint vertexIndex)
{
    uint byteOffset = sceneQuadOffset(quadIndex) + vertexIndex * 20u + 12u;
    return float2(
        asfloat(meshfishScene.Load(byteOffset + 0u)),
        asfloat(meshfishScene.Load(byteOffset + 4u)));
}

uint readSceneQuadFace(uint quadIndex)
{
    return meshfishScene.Load(sceneQuadOffset(quadIndex) + 80u);
}

void emitScenePrimitive(
    uint primitiveIndex,
    OutputVertices<MeshVertex, 4> vertices,
    OutputIndices<uint3, 2> triangles,
    MeshfishDrawCommand draw)
{
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
    if (quadIndex < readSceneModelFirstQuad(modelIndex)
        || quadIndex >= readSceneModelFirstQuad(modelIndex) + readSceneModelQuadCount(modelIndex))
    {
        SetMeshOutputCounts(0, 0);
        return;
    }

    SetMeshOutputCounts(4, 2);
    float3 blockPosition = readSceneBlockPosition(blockIndex);
    float4 color = readSceneBlockColor(blockIndex);

    for (uint i = 0u; i < 4u; i++)
    {
        vertices[i].position = projectPosition(blockPosition + readSceneQuadPosition(quadIndex, i), draw);
        vertices[i].color = color;
        vertices[i].uv = readSceneQuadUv(quadIndex, i);
    }

    triangles[0] = uint3(0u, 1u, 2u);
    triangles[1] = uint3(0u, 2u, 3u);
}

[Shader("mesh")]
[outputtopology("triangle")]
[numthreads(1, 1, 1)]
void main(
    uint3 groupId : SV_GroupID,
    OutputVertices<MeshVertex, 4> vertices,
    OutputIndices<uint3, 2> triangles)
{
    MeshfishDrawCommand draw = meshfishDraws[0];
    uint localPrimitiveIndex = groupId.x;
    uint primitiveIndex = localPrimitiveIndex;
    if ((draw.flags & 2u) != 0u)
    {
        primitiveIndex += draw.firstVertex / 3u;
        if (localPrimitiveIndex >= draw.vertexCount / 3u || primitiveIndex >= scenePrimitiveCount(draw))
        {
            SetMeshOutputCounts(0, 0);
            return;
        }

        emitScenePrimitive(primitiveIndex, vertices, triangles, draw);
        return;
    }

    uint submittedCount = max(draw.indexCount, draw.vertexCount);
    if (primitiveIndex * 3u + 2u >= submittedCount)
    {
        SetMeshOutputCounts(0, 0);
        return;
    }

    SetMeshOutputCounts(3, 1);

    uint vertexIndices[3];
    if ((draw.flags & 1u) != 0u)
    {
        vertexIndices[0] = readIndex(primitiveIndex * 3u + 0u, draw) + draw.baseVertex;
        vertexIndices[1] = readIndex(primitiveIndex * 3u + 1u, draw) + draw.baseVertex;
        vertexIndices[2] = readIndex(primitiveIndex * 3u + 2u, draw) + draw.baseVertex;
    }
    else
    {
        vertexIndices[0] = draw.firstVertex + primitiveIndex * 3u + 0u;
        vertexIndices[1] = draw.firstVertex + primitiveIndex * 3u + 1u;
        vertexIndices[2] = draw.firstVertex + primitiveIndex * 3u + 2u;
    }

    float3 p0 = readPosition(vertexIndices[0], draw);
    float3 p1 = readPosition(vertexIndices[1], draw);
    float3 p2 = readPosition(vertexIndices[2], draw);

    vertices[0].position = projectPosition(p0, draw);
    vertices[0].color = readColor(vertexIndices[0], draw);
    vertices[0].uv = readUv(vertexIndices[0], draw);

    vertices[1].position = projectPosition(p1, draw);
    vertices[1].color = readColor(vertexIndices[1], draw);
    vertices[1].uv = readUv(vertexIndices[1], draw);

    vertices[2].position = projectPosition(p2, draw);
    vertices[2].color = readColor(vertexIndices[2], draw);
    vertices[2].uv = readUv(vertexIndices[2], draw);

    triangles[0] = uint3(0u, 1u, 2u);
}
