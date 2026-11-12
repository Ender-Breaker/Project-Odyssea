# Project Odyssea — `odyssea-core` : architecture système

> Cible : **Minecraft 1.21.1 · NeoForge 21.1.x · Java 21 · mappings Mojang (+ Parchment) · Veil (branche 1.21)**
> Mod id : `odyssea`. Un mod id NeoForge doit respecter `^[a-z][a-z0-9_]{1,63}$` : `odyssea-core` n'est donc pas valide, le trait d'union reste réservé au nom de l'artefact.

---

## 0. Décisions en bref

| Sujet | Décision | Raison principale |
|---|---|---|
| Loader | **NeoForge 1.21.1** | Écosystème tech (Create 6, Mekanism, AE2, Create: Aeronautics/Sable), capabilities `IFluidHandler`/`IEnergyStorage`, data attachments |
| Vaisseaux | **Modèle physique maison + backend « sub-level » derrière une interface** (`ShipBackend`) | Le modèle Newton + Flight Assist, la gravité locale et le transfert inter-dimension doivent rester sous notre contrôle |
| Backend de prototypage | Sable (API publique, NeoForge 1.21.1) à évaluer en premier ; VS2 seulement via des portages non officiels | VS2 n'a pas de release officielle NeoForge 1.21.1 |
| Rendu céleste | Programme Veil (`pinwheel/shaders/program`) déclenché à l'étape `AFTER_SKY` de Veil | Rechargement à chaud, gestion de programmes, compatible avec la chaîne de rendu vanilla |
| Calculs lourds | **Snapshot sur le thread serveur → worker → application sur le thread serveur** | Ne jamais lire un `ServerLevel` hors de son thread |
| Réseaux de fluides et d'énergie | Graphe de composantes connexes, recalculé uniquement à la mutation ; résolution en O(endpoints) par tick | Aucune itération bloc par bloc |

---

## 1. Analyse technique

### 1.1 Fabric ou NeoForge

| Critère | Fabric 1.21.1 | NeoForge 1.21.1 | Poids pour Odyssea |
|---|---|---|---|
| Écosystème tech/survie du modpack | Create (portage Fabric en retard), pas de Mekanism | Create 6, Mekanism, AE2, Immersive Engineering, Create: Aeronautics | **Critique** : le pack s'appuie sur des mods tech tiers |
| Interopérabilité fluides/énergie | Transfer API (`Storage<FluidVariant>`), Team Reborn Energy | `BlockCapability` (`Capabilities.FluidHandler.BLOCK`, `Capabilities.EnergyStorage.BLOCK`), standard de fait pour les mods tech | Haut : nos tuyaux doivent se brancher sur les machines des autres mods |
| Données attachées (entités, chunks, niveaux) | Cardinal Components (tiers) | Data Attachments natifs + `SavedData` | Moyen |
| Réseau | Payloads `CustomPacketPayload` | Identique, plus `PayloadRegistrar` versionné | Neutre |
| Veil | Supporté | Supporté (modules `common` / `fabric` / `neoforge`) | Neutre |
| Sodium / Iris | Plateforme d'origine | Supportés officiellement depuis Sodium 0.6 / Iris 1.8 | Neutre (à vérifier pour les versions figées du pack) |
| Physique de vaisseaux | VS2 Fabric 1.20.1 seulement | Sable (Create: Aeronautics), portages VS2 non officiels | Haut |
| Patching | Mixin seul | Mixin, events, patchs binaires de NeoForge (`onBlockStateChange`, events de tick et de rendu) | Moyen |
| Légèreté, mises à jour rapides | Meilleur | Bon | Faible : le pack se fige sur une version |

**Verdict : NeoForge 1.21.1.** L'argument décisif tient aux capabilities : un modpack spatial hardcore mélange forcément nos réseaux avec des machines tierces, et `BlockCapability` est le contrat que ces mods exposent. La 1.21.1 reste aussi la version « LTS de fait » du modding moderne : c'est là que se trouvent Create 6, Create: Aeronautics et leur couche physique Sable.

Le code métier (atmosphère, physique, graphe réseau) n'utilise que des classes vanilla en mappings Mojang. Seuls `OdysseaCore`, `OdysseaClient` et le mixin touchent à NeoForge, ce qui garde ouverte la porte d'un module `fabric/` plus tard (structure multiloader à la Veil).

### 1.2 Valkyrien Skies 2 / Eureka, Sable ou moteur custom

Ce que doit offrir un moteur de vaisseaux « blocs libres » :

1. **Stockage sub-level** : les blocs du vaisseau vivent dans une zone dédiée de la dimension (le « shipyard », à des millions de blocs de l'origine), avec une transformation monde vers local.
2. **Rendu** des sections transformées. Le renderer de chunks de Sodium ne sait pas dessiner une section avec une matrice arbitraire, il faut un pipeline de VBO dédié et une compatibilité Iris.
3. **Collisions** vaisseau/terrain, vaisseau/vaisseau, entités debout sur un vaisseau en mouvement.
4. **Interaction** : raycast dans le shipyard, pose/casse de blocs, menus des block entities.
5. **Réseau** : synchro des transforms, interpolation client, gestion du tracking.
6. **Persistance** et **transfert inter-dimension** (indispensable à la transition sol-espace).

| | VS2 + Eureka | Sable | Moteur custom |
|---|---|---|---|
| Disponibilité NeoForge 1.21.1 | Portages **non officiels** uniquement | Oui (API publique, utilisée par des addons tiers) | Oui, puisqu'on l'écrit |
| Contrôle de l'intégrateur (Newton + FA, gravité par vaisseau) | Partiel : forces injectées via l'API, solveur interne en Kotlin | Partiel, à évaluer | **Total** |
| Transfert inter-dimension | Non prévu nativement | À évaluer | Conçu pour |
| Points 1 à 5 déjà résolus | Oui (des années-personnes de travail) | Oui | Non : 6 à 12 mois pour une équipe réduite |
| Risque de dépendance | Élevé (fork non officiel, compat Iris signalée cassée sur certaines builds) | Moyen (projet jeune, API qui évolue) | Interne |

**Recommandation : architecture hybride.**

- Le **modèle physique** (`OdysseaShipPhysics`, `ShipShieldSystem`) est **indépendant du backend** : il calcule masses, inerties, poussées, allocation des propulseurs, consommation et intègre un corps rigide. Il produit des forces/couples ou une transform, rien de plus.
- Une interface `ShipBackend` (stockage sub-level, rendu, collisions, transfert) sépare le modèle de son support :
  - **Phase 1 (prototype, 2 à 3 mois)** : backend Sable si son API permet d'appliquer des forces externes et de désactiver sa gravité. On valide le gameplay (FA, boucliers, pressurisation à bord) sans écrire de renderer.
  - **Phase 2** : backend `odyssea.physics.sublevel` maison, avec un périmètre réduit au strict nécessaire. En orbite il n'y a **pas de terrain**, donc les collisions se limitent à vaisseau/astéroïde (OBB contre sphères) et vaisseau/vaisseau. Sur planète : balayage OBB contre voxels avec un pas de 1/20 s, train d'atterrissage.
- La **pressurisation** et le **réseau de tuyaux** travaillent dans les coordonnées du shipyard. Comme ce sont de vrais blocs dans un vrai `ServerLevel`, `AtmosphereManager` et `GasFluidNetwork` fonctionnent **sans aucune adaptation** à bord d'un vaisseau.

---

## 2. Organisation modulaire

```
odyssea-core/
├── docs/ARCHITECTURE.md
└── src/main/
    ├── java/odyssea/
    │   ├── OdysseaCore.java              @Mod commun : events de tick serveur, tags, arrêt des workers
    │   ├── client/OdysseaClient.java     @Mod(dist = CLIENT) : branchement du rendu Veil
    │   ├── mixin/ServerLevelMixin.java   onBlockStateChange vers AtmosphereManager (brèches, portes)
    │   │
    │   ├── dimension/                    ── données planétaires partagées serveur/client
    │   │   ├── PlanetaryAtmosphere       composition → pression(y), O₂, Rayleigh/Mie dérivés des gaz
    │   │   ├── PlanetaryAtmospheres      atmosphères du système solaire, registre dimension → atmosphère
    │   │   └── Planets                   corps dotés d'une dimension : gravité, durée du jour, distance au Soleil
    │   │   (à venir : SeamlessTransitionController, OrbitalCoordinateMapper, GravityField)
    │   │
    │   ├── worldgen/                     ── relief des planètes (voir docs/WORLDGEN.md)
    │   │   ├── density/                  odyssea:fractal, craters, volcanoes, terrace, carve
    │   │   ├── noise/                    simplex 2D à gradient analytique, sommes fractales
    │   │   └── preview/                  /odyssea preview et serveur de prévisualisation
    │   │
    │   ├── atmosphere/                   ── survie (serveur uniquement)
    │   │   ├── AtmosphereManager         SavedData par niveau : diffuseurs, pièces, brèches, organismes
    │   │   ├── SealedRoom                volume, masse de gaz, frontière, fuites (écoulement sonique)
    │   │   ├── FloodFill                 flood-fill 3D borné 64³ (thread worker)
    │   │   ├── RegionSnapshot            copie thread-safe des PalettedContainer
    │   │   └── Airtightness              règles d'étanchéité par face + tags
    │   │   (à venir : client/SpaceAcousticsFilter : OpenAL EFX passe-bas et réverbération)
    │   │
    │   ├── physics/                      ── vaisseaux (serveur ; le client ne fait qu'interpoler)
    │   │   ├── OdysseaShipPhysics        masse/inertie incrémentales, allocation des RCS, FA, intégration
    │   │   └── ShipShieldSystem          réserve tampon, chaleur, surcharge, dégâts localisés
    │   │   (à venir : ShipBackend, sublevel/*, helm/UnifiedHelm, helm/StationConsoles)
    │   │
    │   ├── pipenetwork/                  ── fluides, gaz et énergie
    │   │   └── GasFluidNetwork           graphe de composantes, solveur par priorités, persistance
    │   │   (à venir : adaptateurs BlockCapability IFluidHandler / IEnergyStorage)
    │   │
    │   └── render/veil/                  ── client uniquement
    │       └── AtmosphereRenderPass      uniforms, ordonnancement des astres, passe AFTER_SKY
    │
    └── resources/
        ├── META-INF/neoforge.mods.toml
        ├── odyssea.mixins.json
        ├── assets/odyssea/pinwheel/shaders/program/
        │   ├── planetary_atmosphere.json   programme Veil
        │   ├── planetary_atmosphere.fsh    fragment shader (le « .frag » demandé : Veil impose .fsh)
        │   └── sky_fullscreen.vsh          triangle plein écran (gl_VertexID)
        ├── assets/odyssea/lang/{en_us,fr_fr}.json
        └── data/
            ├── odyssea/damage_type/hypoxia.json
            ├── odyssea/tags/{block,item,entity_type}/...
            └── minecraft/tags/damage_type/{bypasses_armor,no_knockback}.json
```

**Règles de dépendance (vérifiables par ArchUnit en CI) :**

```
render.veil ──► dimension            (lecture des profils de diffusion)
atmosphere  ──► dimension            (pression ambiante, O₂)
physics     ──► dimension            (gravité, densité de l'air)
pipenetwork ──► (rien)               fournit des Endpoint ; diffuseurs et réservoirs s'y branchent
physics     ─✕─ atmosphere           couplage par le monde : un bloc détruit crée une brèche via le mixin
*           ─✕─ render.*             aucune classe serveur ne référence le client
```

Le couplage bouclier → atmosphère passe **par le monde** et non par des appels directs. `ShipShieldSystem` calcule les blocs détruits, le backend les retire (`ServerLevel.destroyBlock`), `ServerLevelMixin` voit le changement et `AtmosphereManager` ouvre la brèche. La dépressurisation suit sans qu'aucun des deux modules ne connaisse l'autre.

---

## 3. Stratégie tick et threading

### 3.1 Règles

1. **Aucune lecture de `ServerLevel` hors du thread serveur.** `getBlockState` peut déclencher un chargement de chunk synchrone, et `PalettedContainer` n'est pas thread-safe en écriture.
2. Un calcul déporté reçoit un **snapshot immuable** et rend un **résultat pur**, appliqué au début du tick suivant.
3. Chaque résultat porte une **génération**. S'il est périmé (le monde a changé dans la zone pendant le calcul), il est jeté et le calcul replanifié.
4. Un **budget par tick** borne le travail du thread serveur (au plus 4 snapshots par tick).

### 3.2 Pipeline d'un tick serveur (par `ServerLevel`, `LevelTickEvent.Post`)

```
Thread serveur                                         Pool « Odyssea-Atmosphere-N »
──────────────────────────────────────────────────    ───────────────────────────────
1. GasFluidNetwork.tick
   ├─ reconstruit les listes d'endpoints des
   │  composantes marquées sales (mutation seulement)
   └─ résout chaque composante : O(endpoints)
2. AtmosphereManager.tick
   ├─ draine la ConcurrentLinkedQueue des résultats ◄─── FloodFill.run(snapshot)
   │    génération périmée ? → re-marquer (debounce)        BFS 64³, BitSet 32 Ko,
   │    scellé ? → installRoom (transfert de masse)         masques d'étanchéité en cache
   ├─ planifie ≤ 4 flood-fills dont le debounce (10 ticks)
   │  est écoulé : capture RegionSnapshot (copie des ──────► CompletableFuture.supplyAsync
   │  PalettedContainer, ~125 sections au plus)
   ├─ fuites : intégration exponentielle O(pièces)
   ├─ aspiration et sifflement des pièces percées
   └─ tous les 10 ticks : hypoxie des entités vivantes
3. Physique des vaisseaux (backend) : pas fixe 1/20 s,
   4 sous-pas, synchro des transforms aux joueurs
```

Les changements de blocs (`ServerLevelMixin`, appelé pour **chaque** `setBlock`) suivent un chemin rapide en O(1) :

- deux maps vides, donc retour immédiat (cas de 99 % des changements) ;
- index grossier des diffuseurs (cellules de 64³), au plus 8 lookups ;
- index des pièces par `SectionPos`, au plus 7 lookups ;
- une cellule frontière qui s'ouvre est **classée tout de suite** sur le thread serveur par une sonde BFS bornée à 2048 cellules : brèche vers l'extérieur (la fuite démarre ce tick) ou poche fermée (re-flood asynchrone qui fusionnera les volumes).

### 3.3 Ce qui reste sur le thread serveur, et pourquoi

| Système | Thread | Justification |
|---|---|---|
| Flood-fill de pressurisation | **Worker** | Jusqu'à 262 144 cellules ; snapshot peu coûteux (copie de palettes) |
| Classement d'une brèche | Serveur | Doit agir dans le tick même ; sonde bornée (< 0,5 ms) |
| Fuites et pression | Serveur | O(nombre de pièces), quelques flottants par pièce |
| Mutation du graphe de tuyaux | Serveur | Rare (pose/casse) ; le BFS de scission est en O(taille de la composante) |
| Résolution des flux | Serveur | O(endpoints) ; les callbacks touchent des block entities, donc thread serveur obligatoire |
| Physique des vaisseaux | Serveur | Doit lire et écrire le monde (collisions, dégâts) au même tick |
| Rendu céleste | Thread de rendu | Contexte GL |

Le pool d'atmosphère compte `min(4, cœurs/4)` threads démons de priorité réduite. Il est recréé à la volée, car un monde solo peut être rouvert dans la même JVM, et arrêté sur `ServerStoppedEvent`.

---

## 4. Transition sol-espace (implémentée, voir `docs/SHIPS.md`)

**Contrainte réelle.** Le client ne détient qu'un seul `ClientLevel`. Tout changement de dimension envoie un `ClientboundRespawnPacket`, qui détruit le niveau client et en recrée un : le passage est bref, jamais réellement continu.

1. **Hauteur** : toutes les dimensions vont de Y = −64 à Y = 2031, la limite du moteur.
2. **Espace** : la dimension `odyssea:space` contient le système solaire entier, comprimé (`space.SpaceSystem` : un bloc = un km visuel, Soleil à l'origine, Terre à 100 000 blocs). Monter au-dessus de Y = 1960 sur une planète mène dans l'espace au-dessus du même point de la sphère ; franchir le sommet d'une atmosphère dans l'espace mène à Y = 1850 dans la dimension de la planète (longitude → X, latitude → Z).
3. **Continuité** : vitesse et orientation sont transportées dans le repère tangent local (est, haut, nord), le vaisseau garde ses passagers (`Entity.changeDimension` les transfère et les rassoit).
4. **Rendu** : dans l'espace, `AtmosphereRenderPass` passe en mode héliocentrique : la caméra est à sa position réelle dans le système, le Soleil change de diamètre et d'éclairement avec la distance, les planètes sont dessinées avec leur atmosphère et leurs anneaux.
5. **Saut quantique** : le vaisseau reste physiquement au départ pendant le trajet ; le ciel est dessiné depuis le point atteint (`ShipControls.quantumOffset`), puis le serveur téléporte le vaisseau et son équipage à l'arrivée.

---

## 5. Gravité duale, EVA, acoustique (conception)

- **Gravité artificielle** : chaque vaisseau expose un repère « plancher ». Pour une entité dont l'AABB intersecte le volume pressurisable du vaisseau, on remplace la gravité par `−g·up_ship` (attribut `GRAVITY` neutralisé, plus une impulsion par tick). À l'extérieur, en dimension orbitale, la gravité vaut zéro.
- **EVA** : le propulseur RCS d'armure consomme O₂ et carburant depuis les composants de données du plastron. La même allocation par moindres carrés que les vaisseaux s'applique, avec 6 tuyères virtuelles.
- **Acoustique** : la pression locale est synchronisée au client par payload, à 2 Hz ou sur changement de plus de 5 %. Côté client, un mixin sur `Channel` applique un filtre EFX `AL_FILTER_LOWPASS`, avec un gain HF ∝ √(P/P₀) et un gain nul dans le vide. Un slot `AL_EFFECT_EAXREVERB` est attaché lorsque le joueur se trouve dans une `SealedRoom` (temps de réverbération dérivé du volume, aspect métallique).

---

## 6. Contenu de ce dépôt

- **Atmosphère et pressurisation**, **graphe de fluides**, **modèle physique et bouclier** (parties 2, 3, 5).
- **Ciels planétaires** (passe Veil, `render.veil`), **génération des mondes** (`worldgen`, voir `docs/WORLDGEN.md`).
- **Vaisseaux** (`ship`) : structure lue d'une schématique Sponge (`SchematicReader`, `ShipStructure`), modèles du datapack (`ShipDesigns`), entité pilotable (`ShipEntity`, vol `ShipFlight`, pirates `PirateBrain`), tirs (`ShipProjectile`), balises de déploiement.
- **Espace** (`space`) : système solaire pilotable, navigation, rencontres ; astéroïdes (`worldgen.feature.AsteroidFeature`).
- **Combinaison spatiale** (`suit`) : oxygène, gravité par corps, sortie extravéhiculaire.
- **Horizon** (`horizon.HorizonSampler` côté serveur, `client.HorizonClient` côté client).
- **Réseau** (`network.OdysseaNetwork`) : état du pilote, actions, interactions, téléportation, horizon.
- **Client** (`client.ship`, `client.map`) : rendu des vaisseaux (maillages en cache par modèle), HUD, commandes, carte du système.

Le vaisseau de ce dépôt est une **entité** (pas un sous-niveau de blocs) : on n'y marche pas en vol, l'équipage est assis. La cabine est considérée comme pressurisée tant que la coque tient.
