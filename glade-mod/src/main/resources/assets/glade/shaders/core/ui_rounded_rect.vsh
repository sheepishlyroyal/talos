#version 330

// Uniform blocks mirror vanilla core/gui.vsh — these are the only UBOs the
// 1.21.11 GuiRenderer binds on its render pass (plus Fog, which we don't use).
layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
};

layout(std140) uniform Projection {
    mat4 ProjMat;
};

// Attribute names must match the VertexFormat element names in
// GladeRenderPipelines.UI_ROUNDED_RECT_FORMAT.
in vec3 Position;
in vec4 Color;
in vec2 UV0;  // offset of this corner from the rect center, GUI px
in ivec2 UV1; // full rect extent (w, h), GUI px
in ivec2 UV2; // (corner radius, border width — 0 means filled), GUI px

out vec4 vertexColor;
out vec2 localPos;
out vec2 halfSize;
out float cornerRadius;
out float borderWidth;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

    vertexColor = Color;
    localPos = UV0;
    halfSize = vec2(UV1) * 0.5;
    cornerRadius = float(UV2.x);
    borderWidth = float(UV2.y);
}
