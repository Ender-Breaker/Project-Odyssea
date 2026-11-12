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
uniform float uStarAngularRadius; // rayon physique : pénombre des ombres
uniform float uStarDiscRadius;    // rayon visuel du disque, exagéré comme le soleil vanilla
uniform int uDrawStarDisc;
uniform vec3 uMoonDirection;
uniform float uMoonGlow;

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
uniform vec3 uMieAnisotropy; // g par canal : un bleu plus diffusé vers l'avant donne les couchers bleus de Mars
uniform vec3 uAbsorption;

// Horizon lointain de Terra : par azimut (pas de 360°/32, azimut 0 = +Z, 90° = +X), albédo du sol et élévation
// de la silhouette du relief au-delà de la distance de rendu.
uniform int uHorizonActive;
uniform vec4 uHorizon[32];
uniform float uHorizonEyeHeight; // hauteur de l'œil au-dessus de la mer, en km à l'échelle réelle (1 bloc = 1 m)

uniform int uHasRings;
uniform float uRingInnerRadius;
uniform float uRingOuterRadius;
uniform vec3 uRingColor;
uniform float uRingOpacity;

#define PRIMARY_STEPS 32
#define SURFACE_STEPS 72
#define TERRAIN_OCTAVES 6

const float PI = 3.14159265358979;
const float NO_HIT = 1e30;
// Radiance du centre du disque relative à l'éclairement : assez pour saturer en blanc au centre,
// assez peu pour que l'assombrissement centre-bord reste visible après le tone mapping.
const float STAR_DISC_RADIANCE = 0.5;
// Portée du halo stellaire, en rayons de disque.
const float STAR_GLOW_EXTENT = 12.0;

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

vec3 phaseMie3(float mu, vec3 g) {
    return vec3(phaseMie(mu, g.r), phaseMie(mu, g.g), phaseMie(mu, g.b));
}

vec2 densityAt(float height) {
    return exp(-max(height, 0.0) / vec2(uRayleighScaleHeight, uMieScaleHeight));
}

vec3 extinction(vec2 opticalDepth) {
    return (uRayleighScattering + uAbsorption) * opticalDepth.x + uMieExtinction * opticalDepth.y;
}

// Profondeur optique d'une atmosphère exponentielle de hauteur d'échelle H, du rayon r vers l'infini sous l'angle
// zénithal de cosinus cosZ : approximation de la fonction de Chapman par C. Schüler (GPU Pro 3), exacte à quelques %
// y compris en incidence rasante. Remplace une marche de 6 pas par rayon de lumière : ~6× moins de calculs par pixel.
// Résultat en km, rapporté à la densité au sol. Sous l'horizon la valeur explose : bornée pour éviter inf × 0 = NaN.
float chapmanDepth(float r, float cosZ, float H) {
    float X = uPlanetRadius / H;
    float h = max(r - uPlanetRadius, 0.0) / H;
    float c = sqrt(X + h);
    float depth;
    if (cosZ >= 0.0) {
        depth = c / (c * cosZ + 1.0) * exp(-h);
    } else {
        float x0 = sqrt(max(1.0 - cosZ * cosZ, 0.0)) * (X + h);
        float c0 = sqrt(x0);
        depth = 2.0 * c0 * exp(min(X - x0, 80.0)) - c / (1.0 - c * cosZ) * exp(-h);
    }
    return clamp(depth, 0.0, 1.0e5) * H;
}

// Profondeur optique (Rayleigh, Mie) du point p (relatif au centre) vers la lumière.
vec2 lightOpticalDepth(vec3 p, vec3 lightDir) {
    float r = length(p);
    float cosZ = dot(p, lightDir) / r;
    return vec2(chapmanDepth(r, cosZ, uRayleighScaleHeight), chapmanDepth(r, cosZ, uMieScaleHeight));
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
// Échantillonnage déterministe au point milieu de chaque pas : l'intégrale varie continûment d'un pixel
// à l'autre. Un jitter par pixel donnait un grain très visible sur les longs rayons rasants (sous l'horizon).
// stepPower > 1 resserre les pas près de la caméra, pour les rayons montants où la densité décroît.
void integrateAtmosphere(vec3 dir, float tStart, float tEnd, float stepPower, out vec3 inscatter, out vec3 transmittance) {
    inscatter = vec3(0.0);
    transmittance = vec3(1.0);
    if (tEnd <= tStart) {
        return;
    }
    float pathLength = tEnd - tStart;
    float mu = dot(dir, uSunDirection);
    vec2 viewDepth = vec2(0.0);
    vec3 sumRayleigh = vec3(0.0);
    vec3 sumMie = vec3(0.0);
    float tPrevious = 0.0;
    for (int i = 0; i < PRIMARY_STEPS; i++) {
        float tNext = pathLength * pow(float(i + 1) / float(PRIMARY_STEPS), stepPower);
        float ds = tNext - tPrevious;
        vec3 p = dir * (tStart + tPrevious + 0.5 * ds) - uPlanetCenter;
        tPrevious = tNext;
        vec2 density = densityAt(length(p) - uPlanetRadius) * ds;
        // Profondeur optique jusqu'au point milieu : la moitié du pas courant seulement.
        vec2 depthToSample = viewDepth + 0.5 * density;
        viewDepth += density;
        float sunVisibility = planetShadow(p, uSunDirection);
        if (sunVisibility <= 0.0) {
            continue;
        }
        vec3 attenuation = exp(-extinction(depthToSample + lightOpticalDepth(p, uSunDirection)))
                         * sunVisibility * ringShadow(p, uSunDirection);
        sumRayleigh += density.x * attenuation;
        sumMie += density.y * attenuation;
    }
    inscatter = uSunColor * uSunIlluminance
              * (sumRayleigh * uRayleighScattering * phaseRayleigh(mu) + sumMie * uMieScattering * phaseMie3(mu, uMieAnisotropy));
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

// Mer de nuages d'une géante gazeuse, vue d'une centaine de kilomètres au-dessus : bandes zonales déformées
// par la turbulence (visibles à l'échelle de l'horizon, quelques milliers de km), cellules convectives et
// tours orageuses à l'échelle de la dizaine de kilomètres. Coordonnées en km sur la sphère.
vec3 cloudDeckAlbedo(vec3 p) {
    vec3 up = normalize(p);
    float latitude = dot(up, uPlanetAxis);
    vec3 q = up * uPlanetRadius;
    float swirl = fbm(q / 1400.0 + vec3(uSeed), 4) - 0.5;
    float band = 0.5 + 0.5 * sin(latitude * 70.0 + swirl * 4.0 + uSeed);
    vec3 albedo = mix(uLowlandColor, uHighlandColor, smoothstep(0.2, 0.8, band));
    float cells = fbm(q / 45.0 + vec3(uSeed * 1.7), 5);
    float towers = smoothstep(0.62, 0.82, fbm(q / 160.0 + vec3(3.1, uSeed, 7.7), 4));
    albedo *= mix(0.72, 1.12, cells);
    return mix(albedo, uHighlandColor * 1.08, towers * 0.6);
}

// Sol de la planète d'accueil au-delà de la distance de rendu : plaine éclairée (ou mer de nuages), voilée
// ensuite par la perspective aérienne. Opaque, pour que rien de vanilla ne transparaisse sous l'horizon.
bool gHorizonAlbedo = false;
vec3 gGroundAlbedo = vec3(0.0);

vec3 shadeHomeGround(vec3 p) {
    vec3 up = normalize(p);
    // Planète à océans (Terre) : le sol lointain mêle mer et terres, sinon il tranche en bande claire à l'horizon.
    vec3 albedo = uSurfaceStyle == 1 ? cloudDeckAlbedo(p)
            : (uOceanSpecular > 0.0 ? mix(uOceanColor, uLowlandColor, 0.35) : uLowlandColor);
    if (gHorizonAlbedo) {
        albedo = gGroundAlbedo;
    }
    float sunHeight = dot(up, uSunDirection);
    vec3 sunTransmittance = exp(-extinction(lightOpticalDepth(p, uSunDirection)));
    float terminator = smoothstep(-0.05, 0.08, sunHeight);
    vec3 direct = uSunColor * uSunIlluminance * sunTransmittance * terminator * max(sunHeight, 0.0) * ringShadow(p, uSunDirection);
    vec3 skyLight = uSunColor * uSunIlluminance * 0.015 * max(sunHeight + 0.25, 0.0);
    return albedo / PI * direct + albedo * skyLight;
}

// atan2(|a×b|, a·b) : précis pour les petits angles, contrairement à acos près de 1.
float angleBetween(vec3 a, vec3 b) {
    return atan(length(cross(a, b)), dot(a, b));
}

// Disque stellaire : sphère lumineuse à bord net et antialiasé, assombrissement centre-bord quadratique
// (u₁ = 0,45, u₂ = 0,25) avec un bord plus chaud, puis un halo serré qui s'éteint à STAR_GLOW_EXTENT rayons.
vec3 starRadiance(vec3 dir) {
    float r = uStarDiscRadius;
    float theta = angleBetween(dir, uSunDirection);
    float edgeWidth = max(uPixelAngle, r * 0.015);
    float coverage = 1.0 - smoothstep(r - edgeWidth, r + edgeWidth, theta);

    float x = min(theta / r, 1.0);
    float mu = sqrt(1.0 - x * x);
    float limb = 1.0 - 0.45 * (1.0 - mu) - 0.25 * (1.0 - mu) * (1.0 - mu);
    vec3 limbTint = mix(vec3(1.0, 0.70, 0.42), vec3(1.0), sqrt(mu));
    vec3 base = uSunColor * uSunIlluminance;
    vec3 disc = base * STAR_DISC_RADIANCE * limb * limbTint;

    float d = max(theta - r, 0.0) / r;
    float fade = 1.0 - smoothstep(0.6 * STAR_GLOW_EXTENT, STAR_GLOW_EXTENT, d);
    vec3 glow = base * (0.05 * exp(-3.0 * d) + 0.012 * exp(-0.5 * d)) * fade;
    return mix(glow, disc, coverage);
}

// Halo de diffusion autour de la lune dans l'atmosphère d'accueil (sa lumière n'est pas intégrée ailleurs).
vec3 moonHalo(vec3 dir) {
    if (uMoonGlow <= 0.0) {
        return vec3(0.0);
    }
    float theta = angleBetween(dir, uMoonDirection);
    return uSunColor * uSunIlluminance * uMoonGlow * (0.6 * exp(-theta / 0.05) + 0.4 * exp(-theta / 0.25));
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
    bool homePass = uRenderSurface == 0;
    // La coupure suit la fin du fondu du halo (STAR_GLOW_EXTENT rayons au-delà du bord) : aucun bord visible.
    bool starVisible = uDrawStarDisc == 1 && dot(dir, uSunDirection) > cos(uStarDiscRadius * (STAR_GLOW_EXTENT + 1.0));

    // Passe sans corps (ciel d'un astre sans atmosphère ni voisin visible) : étoile seule, contribution additive.
    if (uPlanetRadius <= 0.0) {
        if (!starVisible) {
            discard;
        }
        fragColor = vec4(toneMap(starRadiance(dir), jitter), 0.0);
        return;
    }

    float boundRadius = uPlanetRadius + uReliefHeight;
    if (uHasAtmosphere == 1) {
        boundRadius = max(boundRadius, uAtmosphereRadius);
    }
    if (uHasRings == 1) {
        boundRadius = max(boundRadius, uRingOuterRadius);
    }
    vec2 bound = intersectSphere(vec3(0.0), dir, uPlanetCenter, boundRadius);
    if (bound.x > bound.y || bound.y < 0.0) {
        if (!starVisible) {
            discard;
        }
        // Alpha nul en prémultiplié : contribution purement additive.
        fragColor = vec4(toneMap(starRadiance(dir), jitter), 0.0);
        return;
    }

    // Sol : relief raymarché, ou sphère lisse pour la planète d'accueil (le terrain la recouvre à courte distance).
    float tSurface = NO_HIT;
    bool hitSolid = false;
    if (!homePass) {
        hitSolid = marchSurface(dir, tSurface);
    } else {
        vec2 ground = intersectSphere(vec3(0.0), dir, uPlanetCenter, uPlanetRadius);
        if (ground.x < ground.y && ground.y > 0.0) {
            tSurface = max(ground.x, 0.0);
            hitSolid = true;
        }
        if (uHorizonActive == 1) {
            // Interpolation entre les deux azimuts voisins, plus un léger dentelé pour casser les segments droits.
            float azimuth = fract(atan(dir.x, dir.z) / (2.0 * PI)) * 32.0;
            int i0 = int(floor(azimuth)) % 32;
            int i1 = (i0 + 1) % 32;
            vec4 h = mix(uHorizon[i0], uHorizon[i1], fract(azimuth));
            float ridge = (noise1(azimuth * 9.0) - 0.5) * 0.004 + (noise1(azimuth * 37.0) - 0.5) * 0.0015;
            float elevation = asin(clamp(dir.y, -1.0, 1.0));
            gHorizonAlbedo = true;
            gGroundAlbedo = h.rgb;
            // Près du sol, distances à l'échelle réelle : l'altitude exagérée du ciel noierait le sol dans la brume.
            float eye = max(uHorizonEyeHeight, 0.002);
            float trueDip = sqrt(2.0 * eye / uPlanetRadius);
            if (elevation < h.w + ridge) {
                // Relief lointain au-dessus de la ligne de visée (montagnes) : il se dresse à quelques kilomètres.
                tSurface = elevation < 0.0 ? min(eye / tan(-elevation), 2.4) : 1.6;
                hitSolid = true;
            } else if (elevation < -trueDip) {
                tSurface = min(eye / tan(-elevation), 60.0);
                hitSolid = true;
            } else {
                tSurface = NO_HIT;
                hitSolid = false;
            }
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
            // Rayon montant depuis l'intérieur de l'atmosphère : densité décroissante, pas resserrés près de la caméra.
            float stepPower = (shell.x < 0.0 && !hitSolid) ? 3.0 : 1.0;
            integrateAtmosphere(dir, tAtmosphereStart, tAtmosphereEnd, stepPower, inscatter, transmittance);
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
    if (hitSolid) {
        vec3 p = dir * tSurface - uPlanetCenter;
        if (homePass) {
            background = shadeHomeGround(p);
        } else {
            float epsilon = max(tSurface * uPixelAngle, uPlanetRadius * 4e-6);
            float rawHeight = uSurfaceStyle == 0 ? terrainHeight(normalize(p)) : 0.0;
            background = shadeSurface(p, terrainNormal(p, epsilon), dir, rawHeight);
        }
        backgroundAlpha = 1.0;
    } else {
        if (starVisible) {
            background = starRadiance(dir);
        }
        if (homePass) {
            inscatter += moonHalo(dir);
        }
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
