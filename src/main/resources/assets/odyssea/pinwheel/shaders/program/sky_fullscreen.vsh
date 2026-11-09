#version 410 core

out vec2 vNdc;

// Triangle plein écran généré depuis gl_VertexID : VeilRenderSystem.drawScreenQuad() lie un VAO vide et émet 3 sommets.
void main() {
    vec2 uv = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
    vNdc = uv * 2.0 - 1.0;
    gl_Position = vec4(vNdc, 0.0, 1.0);
}
