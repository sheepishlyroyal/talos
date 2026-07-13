#version 330

layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
};

in vec4 vertexColor;
in vec2 localPos;     // fragment offset from rect center, GUI px (interpolated)
in vec2 halfSize;     // rect half extents, GUI px (constant across the quad)
in float cornerRadius;
in float borderWidth; // 0 = filled shape; >0 = ring inset this many px from the edge

out vec4 fragColor;

// Signed distance from p to a rounded box centered at the origin with half
// extents b and corner radius r. Negative inside, positive outside.
float sdRoundBox(vec2 p, vec2 b, float r) {
    vec2 q = abs(p) - b + r;
    return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;
}

void main() {
    float r = min(cornerRadius, min(halfSize.x, halfSize.y));
    float dist = sdRoundBox(localPos, halfSize, r);

    // fwidth(dist) = GUI units per fragment: one-fragment antialiased edge at
    // any GUI scale or resolution.
    float aa = max(fwidth(dist), 1.0e-4);
    float outerCoverage = clamp(0.5 - dist / aa, 0.0, 1.0);

    float coverage = outerCoverage;
    if (borderWidth > 0.0) {
        // Second SDF eval, inset by borderWidth: subtracting its coverage from the
        // outer shape's leaves only the ring. Both edges get the same one-fragment AA.
        float innerDist = dist + borderWidth;
        float innerCoverage = clamp(0.5 - innerDist / aa, 0.0, 1.0);
        coverage = outerCoverage - innerCoverage;
    }

    vec4 color = vertexColor;
    color.a *= coverage;
    if (color.a == 0.0) {
        discard;
    }
    fragColor = color * ColorModulator;
}
