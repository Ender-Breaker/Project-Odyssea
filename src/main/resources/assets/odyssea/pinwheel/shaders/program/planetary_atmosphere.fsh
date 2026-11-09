#version 410 core

// planetary_atmosphere : raymarching d'une planète en relief, diffusion simple Rayleigh + Mie,
// anneaux équatoriaux avec ombres croisées planète ↔ anneaux.
// Unités : kilomètres, repère centré sur la caméra (la caméra est à l'origine).

in vec2 vNdc;
out vec4 fragColor;

uniform mat4 uInvViewProj;
uniform float uPixelAngle;
uniform float uExposure;

uniform vec3 uSunDirection;
uniform vec3 uSunColor;
uniform float uSunIlluminance;
uniform float uStarAngularRadius;
uniform int uDrawStarDisc;

uniform vec3 uPlanetCenter;
uniform float uPlanetRadius;
uniform float uReliefHeight;
uniform vec3 uPlanetAxis;
uniform float uPlanetRotation;
uniform int uRenderSurface;
uniform int uSurfaceStyle;
uniform vec3 uOceanColor;
uniform vec3 uLowlandColor;
uniform vec3 uHighlandColor;
uniform float uSeaLevel;
uniform float uOceanSpecular;
uniform float uSeed;

uniform int uHasAtmosphere;
uniform float uAtmosphereRadius;
uniform vec3 uRayleighScattering;
uniform float uRayleighScaleHeight;
uniform vec3 uMieScattering;
uniform vec3 uMieExtinction;
uniform float uMieScaleHeight;
uniform float uMieAnisotropy;
uniform vec3 uAbsorption;

uniform int uHasRings;
uniform float uRingInnerRadius;
uniform float uRingOuterRadius;
uniform vec3 uRingColor;
uniform float uRingOpacity;

#define PRIMARY_STEPS 24
#define LIGHT_STEPS 6
#define SURFACE_STEPS 72
#define TERRAIN_OCTAVES 6

const float PI = 3.14159265358979;
const float NO_HIT = 1e30;
const float STAR_DISC_RADIANCE = 40.0;

// Forme stable de l'intersection rayon-sphère : h = r² − |oc − (oc·d)d|² au lieu de b² − c,
// qui subit une annulation catastrophique quand la sphère est à des millions de kilomètres.
vec2 intersectSphere(vec3 origin, vec3 dir, vec3 center, float radius) {
    vec3 oc = origin - center;
    float b = dot(oc, dir);
    vec3 q = oc - b * dir;
    float h = radius * radius - dot(q, q);
    if (h < 0.0) {
        return vec2(NO_HIT, -NO_HIT);
    }
    h = sqrt(h);
    return vec2(-b - h, -b + h);
}

float hash13(vec3 p) {
    p = fract(p * 0.1031);
    p += dot(p, p.zyx + 31.32);
    return fract((p.x + p.y) * p.z);
}

float hash11(float p) {
    p = fract(p * 0.1031);
    p *= p + 33.33;
    p *= p + p;
    return fract(p);
}

float valueNoise(vec3 p) {
    vec3 i = floor(p);
    vec3 f = fract(p);
    vec3 u = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);
    float n000 = hash13(i);
    float n100 = hash13(i + vec3(1.0, 0.0, 0.0));
    float n010 = hash13(i + vec3(0.0, 1.0, 0.0));
    float n110 = hash13(i + vec3(1.0, 1.0, 0.0));
    float n001 = hash13(i + vec3(0.0, 0.0, 1.0));
    float n101 = hash13(i + vec3(1.0, 0.0, 1.0));
    float n011 = hash13(i + vec3(0.0, 1.0, 1.0));
    float n111 = hash13(i + vec3(1.0, 1.0, 1.0));
    return mix(mix(mix(n000, n100, u.x), mix(n010, n110, u.x), u.y),
               mix(mix(n001, n101, u.x), mix(n011, n111, u.x), u.y), u.z);
}

float noise1(float x) {
    float i = floor(x);
    float f = fract(x);
    return mix(hash11(i), hash11(i + 1.0), f * f * (3.0 - 2.0 * f));
}

// fbm normalisé dans [0, 1] : Σ 0,5^k = 1 − 0,5^n.
float fbm(vec3 p, int octaves) {
    float sum = 0.0;
    float amplitude = 0.5;
    for (int i = 0; i < octaves; i++) {
        sum += amplitude * valueNoise(p);
        p = p * 2.03 + vec3(17.1, 9.2, 3.7);
        amplitude *= 0.5;
    }
    return sum / (1.0 - 2.0 * amplitude);
}

float interleavedGradientNoise(vec2 pixel) {
    return fract(52.9829189 * fract(dot(pixel, vec2(0.06711056, 0.00583715))));
}

// Formule de Rodrigues.
vec3 rotateAroundAxis(vec3 v, vec3 axis, float angle) {
    float c = cos(angle);
    float s = sin(angle);
    return v * c + cross(axis, v) * s + axis * dot(axis, v) * (1.0 - c);
}

vec3 planetFrame(vec3 direction) {
    return rotateAroundAxis(direction, uPlanetAxis, -uPlanetRotation);
}

// ───────────────────────────── Relief ─────────────────────────────

float terrainHeight(vec3 unitDirection) {
    vec3 q = planetFrame(unitDirection) * 2.2 + vec3(uSeed);
    float warp = fbm(q * 1.7, 3);
    return fbm(q + warp * 0.8, TERRAIN_OCTAVES);
}

float elevationFromHeight(float height) {
    return max(height - uSeaLevel, 0.0) / max(1.0 - uSeaLevel, 1e-3);
}

float terrainSdf(vec3 p) {
    float r = length(p);
    return r - (uPlanetRadius + elevationFromHeight(terrainHeight(p / r)) * uReliefHeight);
}

bool marchSurface(vec3 dir, out float tHit) {
    tHit = NO_HIT;
    if (uReliefHeight <= 0.0) {
        vec2 sphere = intersectSphere(vec3(0.0), dir, uPlanetCenter, uPlanetRadius);
        if (sphere.x < sphere.y && sphere.x > 0.0) {
            tHit = sphere.x;
            return true;
        }
        return false;
    }
    vec2 shell = intersectSphere(vec3(0.0), dir, uPlanetCenter, uPlanetRadius + uReliefHeight);
    if (shell.x > shell.y || shell.y < 0.0) {
        return false;
    }
    float t = max(shell.x, 0.0);
    for (int i = 0; i < SURFACE_STEPS; i++) {
        float d = terrainSdf(dir * t - uPlanetCenter);
        // Tolérance en cône : inutile de converger sous la taille angulaire d'un pixel.
        if (d < max(t * uPixelAngle, uReliefHeight * 1e-4)) {
            tHit = t;
            return true;
        }
        // Pas sous-relaxé : le déplacement fbm n'est pas 1-lipschitzien, un pas plein traverserait les crêtes.
        t += d * 0.6;
        if (t > shell.y) {
            return false;
        }
    }
    tHit = t;
    return true;
}

// Gradient par la méthode du tétraèdre : 4 évaluations au lieu de 6 pour les différences centrées.
vec3 terrainNormal(vec3 p, float epsilon) {
    if (uReliefHeight <= 0.0) {
        return normalize(p);
    }
    const vec2 k = vec2(1.0, -1.0);
    return normalize(k.xyy * terrainSdf(p + k.xyy * epsilon)
                   + k.yyx * terrainSdf(p + k.yyx * epsilon)
                   + k.yxy * terrainSdf(p + k.yxy * epsilon)
                   + k.xxx * terrainSdf(p + k.xxx * epsilon));
}

// ───────────────────────────── Diffusion ─────────────────────────────

float phaseRayleigh(float mu) {
    return 3.0 / (16.0 * PI) * (1.0 + mu * mu);
}

// Cornette-Shanks : Henyey-Greenstein corrigé, normalisé sur la sphère.
float phaseMie(float mu, float g) {
    float g2 = g * g;
    return 3.0 / (8.0 * PI) * ((1.0 - g2) * (1.0 + mu * mu)) / ((2.0 + g2) * pow(max(1.0 + g2 - 2.0 * g * mu, 1e-4), 1.5));
}

vec2 densityAt(float height) {
    return exp(-max(height, 0.0) / vec2(uRayleighScaleHeight, uMieScaleHeight));
}

vec3 extinction(vec2 opticalDepth) {
    return (uRayleighScattering + uAbsorption) * opticalDepth.x + uMieExtinction * opticalDepth.y;
}

// Profondeur optique (Rayleigh, Mie) du point p (relatif au centre) jusqu'à la sortie de l'atmosphère vers la lumière.
vec2 lightOpticalDepth(vec3 p, vec3 lightDir) {
    float tExit = max(intersectSphere(p, lightDir, vec3(0.0), uAtmosphereRadius).y, 0.0);
    float ds = tExit / float(LIGHT_STEPS);
    vec2 depth = vec2(0.0);
    for (int i = 0; i < LIGHT_STEPS; i++) {
        vec3 s = p + lightDir * (ds * (float(i) + 0.5));
        depth += densityAt(length(s) - uPlanetRadius) * ds;
    }
    return depth;
}

// ───────────────────────────── Anneaux et ombres ─────────────────────────────

float ringDensity(float r) {
    float x = (r - uRingInnerRadius) / (uRingOuterRadius - uRingInnerRadius);
    if (x <= 0.0 || x >= 1.0) {
        return 0.0;
    }
    float bands = 0.45 * noise1(x * 38.0 + uSeed) + 0.35 * noise1(x * 131.0 + uSeed * 1.7) + 0.20 * noise1(x * 467.0);
    // Deux divisions (type Cassini et Encke) et des bords adoucis.
    float gaps = smoothstep(0.0, 0.025, abs(x - 0.62)) * (0.35 + 0.65 * smoothstep(0.0, 0.01, abs(x - 0.86)));
    float edges = smoothstep(0.0, 0.05, x) * (1.0 - smoothstep(0.93, 1.0, x));
    return clamp(bands * 1.35, 0.0, 1.0) * gaps * edges;
}

// Ombre des anneaux sur un point p (relatif au centre) : intersection du rayon lumineux avec le plan équatorial.
float ringShadow(vec3 p, vec3 lightDir) {
    if (uHasRings == 0) {
        return 1.0;
    }
    float facing = dot(lightDir, uPlanetAxis);
    if (abs(facing) < 1e-5) {
        return 1.0;
    }
    float t = -dot(p, uPlanetAxis) / facing;
    if (t <= 0.0) {
        return 1.0;
    }
    return 1.0 - clamp(uRingOpacity * ringDensity(length(p + lightDir * t)), 0.0, 1.0);
}

// Ombre de la planète, sphère occultante d'une étoile étendue : la pénombre s'élargit avec la distance
// au limbe selon le rayon angulaire de l'étoile.
float planetShadow(vec3 p, vec3 lightDir) {
    float along = dot(-p, lightDir);
    if (along <= 0.0) {
        return 1.0;
    }
    float closest = length(p + lightDir * along);
    float penumbra = uPlanetRadius * 0.01 + along * uStarAngularRadius;
    return smoothstep(uPlanetRadius - penumbra, uPlanetRadius + penumbra, closest);
}

// Diffusion simple (Nishita) le long du segment [tStart, tEnd] du rayon de vue.
void integrateAtmosphere(vec3 dir, float tStart, float tEnd, float jitter, out vec3 inscatter, out vec3 transmittance) {
    inscatter = vec3(0.0);
    transmittance = vec3(1.0);
    if (tEnd <= tStart) {
        return;
    }
    float ds = (tEnd - tStart) / float(PRIMARY_STEPS);
    float mu = dot(dir, uSunDirection);
    vec2 viewDepth = vec2(0.0);
    vec3 sumRayleigh = vec3(0.0);
    vec3 sumMie = vec3(0.0);
    for (int i = 0; i < PRIMARY_STEPS; i++) {
        vec3 p = dir * (tStart + ds * (float(i) + jitter)) - uPlanetCenter;
        vec2 density = densityAt(length(p) - uPlanetRadius) * ds;
        viewDepth += density;
        float sunVisibility = planetShadow(p, uSunDirection);
        if (sunVisibility <= 0.0) {
            continue;
        }
        vec3 attenuation = exp(-extinction(viewDepth + lightOpticalDepth(p, uSunDirection)))
                         * sunVisibility * ringShadow(p, uSunDirection);
        sumRayleigh += density.x * attenuation;
        sumMie += density.y * attenuation;
    }
    inscatter = uSunColor * uSunIlluminance
              * (sumRayleigh * uRayleighScattering * phaseRayleigh(mu) + sumMie * uMieScattering * phaseMie(mu, uMieAnisotropy));
    transmittance = exp(-extinction(viewDepth));
}

// ───────────────────────────── Surface ─────────────────────────────

vec3 shadeSurface(vec3 p, vec3 normal, vec3 viewDir, float rawHeight) {
    vec3 up = normalize(p);
    float signedLatitude = dot(up, uPlanetAxis);
    float latitude = abs(signedLatitude);
    vec3 albedo;
    bool ocean = false;
    if (uSurfaceStyle == 1) {
        float turbulence = valueNoise(planetFrame(up) * 5.0 + vec3(uSeed)) - 0.5;
        float band = 0.5 + 0.5 * sin(signedLatitude * 26.0 + turbulence * 2.5);
        albedo = mix(uLowlandColor, uHighlandColor, band) * mix(0.85, 1.0, 1.0 - latitude * latitude);
    } else {
        ocean = rawHeight < uSeaLevel;
        float elevation = elevationFromHeight(rawHeight);
        albedo = ocean
                ? uOceanColor * mix(0.55, 1.0, rawHeight / max(uSeaLevel, 1e-3))
                : mix(uLowlandColor, uHighlandColor, smoothstep(0.0, 0.65, elevation));
        if (uHasAtmosphere == 1) {
            float ice = smoothstep(0.78, 0.9, latitude + elevation * 0.25 + 0.08 * (valueNoise(planetFrame(up) * 9.0 + vec3(uSeed)) - 0.5));
            albedo = mix(albedo, vec3(0.90, 0.93, 0.97), ice);
        }
    }

    vec3 sunTransmittance = uHasAtmosphere == 1 ? exp(-extinction(lightOpticalDepth(p, uSunDirection))) : vec3(1.0);
    // Terminateur de la sphère : la normale de relief seule éclairerait des crêtes sur la face nocturne.
    float terminator = smoothstep(-0.05, 0.08, dot(up, uSunDirection));
    vec3 sunLight = uSunColor * uSunIlluminance * sunTransmittance * ringShadow(p, uSunDirection) * terminator;
    vec3 shadingNormal = ocean ? up : normal;
    float ndl = max(dot(shadingNormal, uSunDirection), 0.0);
    vec3 color = albedo / PI * sunLight * ndl;
    if (uHasAtmosphere == 1) {
        color += albedo * uSunColor * uSunIlluminance * 0.015 * max(dot(up, uSunDirection) + 0.25, 0.0);
    }
    if (ocean && uOceanSpecular > 0.0) {
        vec3 halfway = normalize(uSunDirection - viewDir);
        float fresnel = 0.02 + 0.98 * pow(1.0 - max(dot(up, -viewDir), 0.0), 5.0);
        color += sunLight * uOceanSpecular * fresnel * pow(max(dot(up, halfway), 0.0), 180.0) * ndl * 4.0;
    }
    return color;
}

vec3 toneMap(vec3 radiance, float jitter) {
    vec3 mapped = vec3(1.0) - exp(-radiance * uExposure);
    mapped = pow(mapped, vec3(1.0 / 2.2));
    return max(mapped + (jitter - 0.5) / 255.0, vec3(0.0));
}

void main() {
    vec4 farPoint = uInvViewProj * vec4(vNdc, 1.0, 1.0);
    vec3 dir = normalize(farPoint.xyz / farPoint.w);
    float jitter = interleavedGradientNoise(gl_FragCoord.xy);
    bool starDisc = uDrawStarDisc == 1 && dot(dir, uSunDirection) > cos(uStarAngularRadius);

    float boundRadius = uPlanetRadius + uReliefHeight;
    if (uHasAtmosphere == 1) {
        boundRadius = max(boundRadius, uAtmosphereRadius);
    }
    if (uHasRings == 1) {
        boundRadius = max(boundRadius, uRingOuterRadius);
    }
    vec2 bound = intersectSphere(vec3(0.0), dir, uPlanetCenter, boundRadius);
    if (bound.x > bound.y || bound.y < 0.0) {
        if (!starDisc) {
            discard;
        }
        // Alpha nul en prémultiplié : contribution purement additive.
        fragColor = vec4(toneMap(uSunColor * uSunIlluminance * STAR_DISC_RADIANCE, jitter), 0.0);
        return;
    }

    // Sol : relief raymarché, ou simple sphère occultante pour la planète d'accueil (le terrain la recouvre).
    float tSurface = NO_HIT;
    bool hitSolid = false;
    if (uRenderSurface == 1) {
        hitSolid = marchSurface(dir, tSurface);
    } else {
        vec2 ground = intersectSphere(vec3(0.0), dir, uPlanetCenter, uPlanetRadius);
        if (ground.x < ground.y && ground.x > 0.0) {
            tSurface = ground.x;
            hitSolid = true;
        }
    }

    vec3 inscatter = vec3(0.0);
    vec3 transmittance = vec3(1.0);
    float tAtmosphereStart = NO_HIT;
    float tAtmosphereEnd = -NO_HIT;
    if (uHasAtmosphere == 1) {
        vec2 shell = intersectSphere(vec3(0.0), dir, uPlanetCenter, uAtmosphereRadius);
        if (shell.x < shell.y && shell.y > 0.0) {
            tAtmosphereStart = max(shell.x, 0.0);
            tAtmosphereEnd = hitSolid ? min(shell.y, tSurface) : shell.y;
            integrateAtmosphere(dir, tAtmosphereStart, tAtmosphereEnd, jitter, inscatter, transmittance);
        }
    }

    vec4 ring = vec4(0.0);
    float tRing = NO_HIT;
    if (uHasRings == 1) {
        float facing = dot(dir, uPlanetAxis);
        if (abs(facing) > 1e-6) {
            float t = dot(uPlanetCenter, uPlanetAxis) / facing;
            if (t > 0.0 && t < tSurface) {
                vec3 p = dir * t - uPlanetCenter;
                float r = length(p);
                float density = ringDensity(r);
                if (density > 0.0) {
                    float alpha = clamp(uRingOpacity * density, 0.0, 1.0);
                    float sunSide = dot(uPlanetAxis, uSunDirection);
                    bool litFace = sunSide * dot(uPlanetAxis, -dir) > 0.0;
                    // Face éclairée : rétrodiffusion ; face sombre : lumière transmise à travers la couche de particules.
                    float scattering = litFace ? 1.0 : (1.0 - alpha) * 0.6 + 1.5 * phaseMie(dot(dir, uSunDirection), 0.55);
                    float grain = 0.75 + 0.25 * noise1(r * 0.004 + uSeed);
                    vec3 radiance = uRingColor * grain * uSunColor * uSunIlluminance * abs(sunSide) * scattering
                                  * planetShadow(p, uSunDirection) / PI;
                    ring = vec4(radiance * alpha, alpha);
                    tRing = t;
                }
            }
        }
    }

    vec3 background = vec3(0.0);
    float backgroundAlpha = 0.0;
    if (hitSolid && uRenderSurface == 1) {
        vec3 p = dir * tSurface - uPlanetCenter;
        float epsilon = max(tSurface * uPixelAngle, uPlanetRadius * 4e-6);
        float rawHeight = uSurfaceStyle == 0 ? terrainHeight(normalize(p)) : 0.0;
        background = shadeSurface(p, terrainNormal(p, epsilon), dir, rawHeight);
        backgroundAlpha = 1.0;
    } else if (starDisc && !hitSolid) {
        background = uSunColor * uSunIlluminance * STAR_DISC_RADIANCE;
    }

    // Composition avant → arrière en alpha prémultiplié : anneau derrière l'atmosphère, atmosphère, anneau devant.
    bool hasAtmosphereSegment = tAtmosphereEnd > tAtmosphereStart;
    bool hasRing = tRing < NO_HIT;
    bool ringInFront = hasRing && (!hasAtmosphereSegment || tRing < 0.5 * (tAtmosphereStart + tAtmosphereEnd));
    if (hasRing && !ringInFront) {
        background = ring.rgb + (1.0 - ring.a) * background;
        backgroundAlpha = ring.a + (1.0 - ring.a) * backgroundAlpha;
    }
    vec3 color = inscatter + transmittance * background;
    float alpha = 1.0 - (1.0 - backgroundAlpha) * dot(transmittance, vec3(1.0 / 3.0));
    if (ringInFront) {
        color = ring.rgb + (1.0 - ring.a) * color;
        alpha = ring.a + (1.0 - ring.a) * alpha;
    }
    if (alpha <= 1e-4 && max(color.r, max(color.g, color.b)) < 1e-4) {
        discard;
    }
    fragColor = vec4(toneMap(color, jitter), clamp(alpha, 0.0, 1.0));
}
