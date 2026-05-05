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

uint submittedVertexCount(MeshfishDrawCommand draw)
{
    return max(draw.indexCount, draw.vertexCount);
}

bool primitiveInBounds(uint localPrimitiveIndex, MeshfishDrawCommand draw)
{
    if ((draw.flags & 2u) != 0u)
    {
        return localPrimitiveIndex < draw.vertexCount / 3u;
    }

    return localPrimitiveIndex * 3u + 2u < submittedVertexCount(draw);
}

float3 readScenePrimitivePosition(uint primitiveIndex, uint vertexIndex)
{
    uint byteOffset = primitiveIndex * 64u + vertexIndex * 20u;
    return float3(
        asfloat(meshfishScene.Load(byteOffset + 0u)),
        asfloat(meshfishScene.Load(byteOffset + 4u)),
        asfloat(meshfishScene.Load(byteOffset + 8u)));
}

void readPrimitivePositions(uint primitiveIndex, MeshfishDrawCommand draw, out float3 p0, out float3 p1, out float3 p2)
{
    if ((draw.flags & 2u) != 0u)
    {
        p0 = readScenePrimitivePosition(primitiveIndex, 0u);
        p1 = readScenePrimitivePosition(primitiveIndex, 1u);
        p2 = readScenePrimitivePosition(primitiveIndex, 2u);
        return;
    }

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

    p0 = readPosition(vertexIndices[0], draw);
    p1 = readPosition(vertexIndices[1], draw);
    p2 = readPosition(vertexIndices[2], draw);
}

bool sphereInFrustum(float3 center, float radius)
{
    if (meshfishFrame.frustumCullingEnabled != 1)
    {
        return true;
    }

    for (uint i = 0; i < 6; i++)
    {
        float4 plane = meshfishFrame.frustumPlanes[i];
        if (dot(plane.xyz, center) + plane.w < -radius)
        {
            return false;
        }
    }

    return true;
}

bool primitiveInFrustum(uint primitiveIndex, MeshfishDrawCommand draw)
{
    if ((draw.flags & 2u) != 0u)
    {
        return true;
    }

    if (draw.transformMode != 1u && draw.transformMode != 2u)
    {
        return true;
    }

    float3 p0;
    float3 p1;
    float3 p2;
    readPrimitivePositions(primitiveIndex, draw, p0, p1, p2);

    p0 -= meshfishFrame.cameraPosition.xyz;
    p1 -= meshfishFrame.cameraPosition.xyz;
    p2 -= meshfishFrame.cameraPosition.xyz;

    float3 center = (p0 + p1 + p2) / 3.0f;
    float radius = max(length(p0 - center), max(length(p1 - center), length(p2 - center)));
    return sphereInFrustum(center, radius);
}

[Shader("amplification")]
[numthreads(1, 1, 1)]
void main(uint3 groupId : SV_GroupID)
{
    MeshfishDrawCommand draw = meshfishDraws[0];
    uint localPrimitiveIndex = groupId.x;
    if (!primitiveInBounds(localPrimitiveIndex, draw))
    {
        return;
    }

    uint primitiveIndex = localPrimitiveIndex;
    if ((draw.flags & 2u) != 0u)
    {
        primitiveIndex += draw.firstVertex / 3u;
    }

    if (!primitiveInFrustum(primitiveIndex, draw))
    {
        return;
    }

    MeshPayload payload;
    payload.groupOffset = primitiveIndex;
    payload.drawFlags = draw.flags;
    DispatchMesh(1, 1, 1, payload);
}
