# Génération des mondes et ciels planétaires

> Overworld (Terra), Lune, Mercure, Vénus, Mars, Jupiter, Saturne, Uranus, Neptune : une dimension par corps,
> un relief propre et un ciel cohérent avec la position du corps dans le système solaire.

## 1. Hauteur de construction

Toutes les dimensions d'Odyssea, overworld compris, vont de **Y = −64** (comme vanilla) à **Y = 2031**, le maximum du moteur.
`BlockPos` code Y sur 12 bits et `DimensionType` plafonne `min_y + height` à 2032. Dépasser cette limite
demanderait de réécrire le stockage des chunks (à la manière de Cubic Chunks), ce qui sort du cadre d'un mod.

Le relief garde les dimensions de Minecraft vanilla : sommets entre 200 et 300 blocs, fonds marins vers Y 20-30.

| Dimension | `min_y` (bedrock) | Plage de bruit | Plus haut relief | Construction jusqu'à |
|---|---|---|---|---|
| Terra (overworld) | −64 | −64 → 448 | ~300 | 2031 |
| Lune, Mercure, Vénus, Mars | −64 | −64 → 448 | ~300-330 | 2031 |
| Uranus | −64 | −64 → 384 | ~170 | 2031 |
| Jupiter, Saturne, Neptune | −64 (vide) | aucun sol | — | 2031 |

Le coût de génération dépend de la plage de **bruit** (`noise_settings`), pas de la hauteur de la dimension.
Au-dessus du relief, la densité est court-circuitée (`range_choice` sur la pente) : les centaines de blocs d'air
ne coûtent presque rien.

**Monde existant** : la hauteur et le générateur de l'overworld changent. Il faut créer un nouveau monde, car les
anciens chunks gardent l'ancien relief.

**Rendu client** : vanilla 1.21.1 prépare 5 tampons GPU par section de 16 blocs de la zone de rendu, même vide. Avec
131 sections de haut et une distance de 32, cela gelait le jeu au chargement (~9 millions d'objets OpenGL).
`RenderSectionMixin` et `VertexBufferMixin` ne créent un tampon que pour une couche qui contient des blocs.

## 2. Architecture

```
tools/worldgen/            générateur Python (aucune dépendance) → src/main/resources/data/**
  dsl.py                   constructeurs JSON : density functions, splines, règles de surface
  terra.py                 overworld
  planets.py               Lune, Mercure, Vénus, Mars, géantes gazeuses
  generate_worldgen.py     écrit tout (python tools/worldgen/generate_worldgen.py)

odyssea.worldgen.density   briques Java que les fonctions vanilla ne savent pas exprimer
  odyssea:fractal          bruit fractal 2D : fbm / crêtes (Musgrave) / billow, érosion par la pente,
                           déformation de domaine, anisotropie (dunes, fractures)
  odyssea:craters          champs de cratères : loi de puissance des tailles, cuvette simple ou complexe
                           (fond plat, pic central, parois en gradins), remparts, éjectas, usure
  odyssea:volcanoes        boucliers (escarpement basal), cônes, dômes en galette, caldeiras emboîtées
  odyssea:terrace          gradins (mesas, parois stratifiées, fronts de coulées)
  odyssea:carve            creusement vers un plancher (vallées) sans double évaluation
```

Les fichiers de `data/odyssea/worldgen/{noise,density_function,noise_settings,biome}`, `data/odyssea/dimension*`
et `data/minecraft/dimension*` sont **générés** : on modifie les scripts, puis on relance le générateur.

La graine du monde parvient aux briques Java par le bruit `odyssea:seed`, que vanilla relie à la graine. Chaque
fonction combine cette graine avec son `salt`.

### Principe commun

1. Altitude 2D `H(x, z)` en blocs, composée en JSON à partir des briques ci-dessus.
2. Densité 3D `(H − y)/8 + amplitude · bruit 3D` : falaises et surplombs là où l'amplitude est forte.
3. Grottes, puis transitions au fond et au sommet, interpolation : même chaîne que vanilla.
4. Les masques géologiques (mers lunaires, canyons, volcans…) alimentent aussi les paramètres climatiques du
   routeur, ce qui fait que le biome suit exactement la géologie.

Toute fonction 2D est enveloppée dans `flat_cache` + `cache_2d`. Sans le second cache, l'aquifère et la surface
préliminaire recalculeraient tout le relief à chaque pas de 8 blocs pour les colonnes voisines du chunk.

## 3. Terra

Inspirée de l'organisation de Terralith (forme de base + ajouts + soustractions, splines sur continentalité /
érosion / étrangeté). Le relief lui-même est calculé par les briques Java.

| Élément | Construction |
|---|---|
| Continents | échelles vanilla, un peu moins d'océan, côtes moins dentelées |
| Océans | plateau continental, talus, fonds vers Y 18-30, dorsales sous-marines |
| Montagnes | enveloppe (faible érosion × intérieur des terres × pics-et-vallées) × multifractale à crêtes déformée : chaînes sinueuses aux dimensions vanilla (sommets ~200-300) |
| Vallées | creusées le long de W = 0 jusqu'au niveau de la mer près des côtes (fjords, rivières), perchées au cœur des massifs (vallées glaciaires sèches) |
| Plateaux | escarpements d'une cinquantaine de blocs |
| Climat | température abaissée avec l'altitude : forêts en vallée, conifères plus haut, sommets enneigés |
| Surface | règles vanilla complètes + neiges éternelles au-dessus de la limite climatique, roche nue sur les versants raides, éboulis au-dessus de la limite des arbres |
| Biomes | préréglage vanilla `minecraft:overworld`, cohérent avec le relief, qui suit les mêmes paramètres |

Mixin `NoiseBasedChunkGeneratorMixin` : la nappe de lave globale vanilla est fixée à Y = −54 (fond vanilla + 10).
Pour les mondes plus profonds, elle est placée au fond + 10, sinon les océans profonds se rempliraient de lave.

## 4. Planètes

| Corps | Relief | Biomes |
|---|---|---|
| Lune | hautes terres anorthositiques, mers basaltiques (rides), bassins d'impact, 4 échelles de cratères, rainures sinueuses, tunnels de lave | hautes terres, mers |
| Mercure | terrains cratérisés, plaines lisses, escarpements lobés (failles de 150 blocs sur des dizaines de km), bassin géant rare | terrains cratérisés, plaines lisses, creux brillants |
| Vénus | plaines de coulées en gradins, tessères fracturées, boucliers géants, dômes en galette, coronae, lacs de lave sous Y = 40 | plaines volcaniques, tessères, volcans |
| Mars | dichotomie nord/sud, boucliers de Tharsis (Olympus Mons), Valles Marineris (500 blocs, parois stratifiées), chenaux, dunes transverses, calottes polaires stratifiées | plaines boréales, hauts plateaux, mer de dunes, canyon, province volcanique, calotte polaire |
| Uranus | géante de glaces : océan d'eau sous une banquise, crêtes de glace de méthane et d'ammoniac, icebergs, fonds de boue glacée, noyau rocheux profond | océan gelé, banquise, crêtes de glace |
| Jupiter, Saturne, Neptune | aucun sol : dimension vide, la mer de nuages est dessinée par le shader ~25 km sous Y = 0 | couche de nuages |

## 5. Ciels

Décrits par `render.veil.SolarSystemSky`, rendus par la passe Veil existante.

- **Soleil** : diamètre apparent ∝ 1/d (2,6 × depuis Mercure, un point depuis Neptune), éclairement en 1/d².
  L'exposition compense en partie ((E₀/E)^0,65), comme l'œil qui s'adapte.
- **Lunes** : rayons, distances et périodes réels, avec l'agrandissement de la lune vanilla (×7,7). Phobos se lève
  à l'ouest deux fois par jour martien, Triton (orbite rétrograde) file d'est en ouest, et les satellites galiléens
  défilent au-dessus de Jupiter.
- **Terre vue de la Lune** : immobile dans le ciel (rotation synchrone), avec ses phases.
- **Saturne** : les anneaux barrent le ciel et portent l'ombre de la planète.
- **Atmosphères** : Mars a un ciel caramel et des couchers bleus (anisotropie de Mie par canal). Vénus est couverte
  d'un voile orangé sans disque solaire. Uranus et Neptune sont cyan et bleu, à cause de l'absorption du méthane.
- **Durée du jour** (`dimension.Planets`, mixin `LevelTimeOfDayMixin`) : valeur réelle pour les rotations rapides.
  Les rotations lentes sont comprimées comme le mois lunaire vanilla (29,5 j → 8 j) : 8 jours pour la Lune,
  48 pour Mercure, 32 pour Vénus. Vénus et Uranus ont une rotation rétrograde : le Soleil s'y lève à l'ouest.
- **Environnement** (`client.PlanetEnvironment`) : pas de brouillard sur les corps sans atmosphère, voile ocre sur
  Mars, brume épaisse sur Vénus, aucun nuage vanilla hors de la Terre, étoiles visibles de jour sur la Lune et
  Mercure, lumière teintée (ocre sur Mars, orangée et tamisée sur Vénus).

## 6. Outils

- `/odyssea preview [pas]` (opérateur) : rend le terrain autour de soi dans `odyssea_previews/` (relief, biomes,
  coupe verticale, surface réelle).
- `./gradlew runPreviewServer` : serveur sans interface qui rend toutes les dimensions dans
  `run/preview/odyssea_previews/` puis s'arrête (arrêt forcé au bout de 20 min). Supprimer `run/preview/world`
  entre deux essais. Options : `-PpreviewOnly=overworld,mars`, `-PpreviewX=…`, `-PpreviewZ=…`. Le détail, la coupe
  et la surface sont centrés sur le point culminant de la vue d'ensemble. Mettre `max-tick-time=-1` dans
  `run/preview/server.properties` : la génération synchrone des chunks de surface peut dépasser le watchdog.
- Gradle exige un JDK 21 : `JAVA_HOME="C:/Program Files/Java/jdk-21"` si le `java` par défaut est un Java 8.

## 7. Blocs planétaires

18 blocs (`odyssea.registry.OdysseaBlocks`, onglet créatif « Odyssea – Planètes »), textures 16×16 procédurales
générées par `python tools/assets/generate_blocks.py` (modèles, tables de butin, tags et traductions compris) :
régolithes lunaire, des mers lunaires, mercurien, vénusien et martien (chutent comme le sable), anorthosite,
basaltes lunaire, vénusien et martien, roches mercurienne et martienne, croûtes de soufre et des creux, glaces
carbonique, uranienne, de méthane et d'ammoniac, boue glacée. Les roches sont dans `stone_ore_replaceables` : les
minerais vanilla s'y forment.

## 8. Performances

- Plage de bruit limitée au relief (−64 → 448) : génération 2 à 4 fois plus rapide qu'avec les grands reliefs.
- Fonctions 2D en `flat_cache` + `cache_2d`, hachages des density functions précalculés.
- Shader céleste : profondeur optique vers le Soleil par l'approximation de Chapman (Schüler) au lieu d'une marche de
  6 pas par échantillon, ~6× moins de calcul par pixel.
- Tampons GPU paresseux pour les sections de terrain (voir §1).

**Vulkan** : Minecraft 1.21.1 ne dispose que d'un moteur de rendu OpenGL. Un portage Vulkan revient à réécrire tout
le moteur de rendu (c'est l'objet du mod VulkanMod, réservé à Fabric et incompatible avec Veil, Sodium et Iris).
Pour de meilleures performances, Sodium (NeoForge) reste la voie recommandée ; la passe céleste Veil y est
compatible.

## 9. Test du client

`./gradlew runSmokeClient --offline "-PsmokeTour=odyssea:mercury,odyssea:mars"` crée un monde jetable
`odyssea_smoke`, mesure le chargement et les FPS, visite chaque dimension en spectateur un matin local et enregistre
trois captures par dimension dans `run/screenshots/`. Le jeu se ferme seul ; ne pas changer de fenêtre pendant le
test (la mise en pause l'interrompt).
