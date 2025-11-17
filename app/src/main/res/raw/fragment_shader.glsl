precision highp float;

uniform vec2 u_resolution;
uniform float u_time;
uniform vec2 u_view_center;
uniform vec2 u_view_size;
uniform float u_cos_angle;
uniform float u_sin_angle;
uniform float u_swap_coords;  // 1.0 for portrait (swap), 0.0 for landscape
uniform int u_max_iterations;
uniform vec2 u_target;  // Zoom target point in complex plane

// Mandelbrot calculation constants
uniform float u_escape_radius_squared;  // Squared escape radius (2.0^2)

// Color constants
const float COLOR_SCALE = 0.01;           // Scale factor for iteration-based color
const float COLOR_TIME_SPEED = 0.1;      // Speed of color cycling over time
const float COLOR_SATURATION = 1.0;       // HSV saturation (0.0-1.0)
const float COLOR_VALUE = 1.0;            // HSV value/brightness (0.0-1.0)
const float SMOOTH_COLOR_OFFSET = 4.0;    // Offset for smooth coloring calculation

// HSV to RGB conversion for smooth coloring
vec3 hsv2rgb(vec3 c) {
    vec4 K = vec4(1.0, 2.0 / 3.0, 1.0 / 3.0, 3.0);
    vec3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www);
    return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);
}

void main() {
    // Swap coordinates for portrait orientation
    vec2 fragCoord = mix(gl_FragCoord.xy, gl_FragCoord.yx, u_swap_coords);

    // Map pixel coordinates to normalized device coordinates
    vec2 uv = fragCoord / u_resolution.xy;
    uv = uv * 2.0 - 1.0;  // Convert to -1 to 1 range

    // Adjust aspect ratio
    float aspect = u_resolution.x / u_resolution.y;
    uv.x *= aspect;

    // Map to view size (accounting for aspect ratio difference)
    float viewAspect = u_view_size.x / u_view_size.y;
    if (aspect > viewAspect) {
        // Screen is wider than view - scale by height
        uv *= u_view_size.y * 0.5;
    } else {
        // Screen is taller than view - scale by width
        uv.x *= u_view_size.x * 0.5;
        uv.y *= u_view_size.x * 0.5 / aspect;
    }

    // Apply rotation around zoom target point (using pre-calculated cos/sin from CPU)
    // First translate to view center, then get position relative to target
    vec2 rel_to_target = uv + u_view_center - u_target;

    // Rotate around target (inverse rotation - when view rotates CW, we sample CCW)
    vec2 rotated;
    rotated.x = rel_to_target.x * u_cos_angle + rel_to_target.y * u_sin_angle;
    rotated.y = -rel_to_target.x * u_sin_angle + rel_to_target.y * u_cos_angle;

    // Translate back from target to get final complex plane coordinate
    vec2 c = rotated + u_target;

    // Mandelbrot iteration
    vec2 z = vec2(0.0, 0.0);
    int iterations = 0;

    for (int i = 0; i < u_max_iterations; i++) {
        if (dot(z, z) > u_escape_radius_squared) break;

        // z = z^2 + c
        float temp = z.x * z.x - z.y * z.y + c.x;
        z.y = 2.0 * z.x * z.y + c.y;
        z.x = temp;

        iterations = i;
    }

    // Color based on iterations
    if (iterations >= u_max_iterations - 1) {
        // Point is in the set - render black
        gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
    } else {
        // Smooth coloring using continuous iteration count
        float sl = float(iterations) - log2(log2(dot(z, z))) + SMOOTH_COLOR_OFFSET;

        // Create colorful gradient
        float hue = fract(sl * COLOR_SCALE + u_time * COLOR_TIME_SPEED);

        vec3 color = hsv2rgb(vec3(hue, COLOR_SATURATION, COLOR_VALUE));
        gl_FragColor = vec4(color, 1.0);
    }
}