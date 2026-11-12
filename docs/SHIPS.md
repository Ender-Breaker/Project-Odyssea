# Vaisseaux, espace et combinaison spatiale

## 1. Vaisseaux

Un vaisseau est une structure de blocs lue depuis une schématique Sponge (`.schem`, export WorldEdit ou
Litematica/Forgematica), rendue d'un seul tenant et pilotée comme une entité.

| Modèle | Schématique | Taille | Rôle |
|---|---|---|---|
| Cutlass | `drakecutlassblack.schem` | 53 × 16 × 39, 6 200 blocs | cargo armé, 8 places |
| Destroyer | `helldiverssuperdestroyer.schem` | 118 × 65 × 302, 87 000 blocs | croiseur lourd, 6 places |
| Chasseur pirate | généré par `tools/ships/generate_raider.py` | 9 × 3 × 12 | ennemi de l'espace |

Les schématiques fournies dans `Jar extratc/schematic spaceship` ont été copiées dans
`src/main/resources/data/odyssea/ships/`. Ce sont des répliques de vaisseaux d'autres jeux : à remplacer avant une
diffusion publique du mod.

### Obtenir et déployer un vaisseau

- **Balise de déploiement** (onglet créatif « Odyssea – Vaisseaux » ou recette) : clic droit sur un bloc pour faire
  apparaître le vaisseau devant soi, ou dans le vide dans l'espace.
- **Embarquer** : clic droit sur la coque. Le premier à bord pilote, les suivants occupent les sièges d'équipage.
- **Remballer** : accroupi, main vide, clic droit sur un vaisseau posé et vide. La balise garde l'état des réservoirs.
- **Ravitailler** (clic droit sur la coque, objet en main) : pile à hydrogène (+25 % d'hydrogène), cellule quantique
  (+25 %), kit de réparation (+25 % de coque), missiles (+2).

### Commandes (modifiables dans Options › Commandes › Odyssea – Vaisseaux)

| Action | Touche par défaut |
|---|---|
| Cap | souris : le vaisseau tourne vers l'endroit regardé |
| Poussée avant / arrière | avancer / reculer |
| Dérapage | gauche / droite |
| Monter / descendre | sauter / s'accroupir |
| Postcombustion | sprint |
| Roulis | Z / C (position QWERTY ; W / C sur AZERTY) |
| Canons / missile | clic gauche / clic droit |
| Verrouiller une cible | R |
| Assistance de vol | X |
| Répartition d'énergie | G |
| Saut quantique | J |
| Regard libre | Alt gauche (maintenir) |
| Quitter le siège | V |
| Soute | B (à bord) ; à quai : accroupi, objet en main, clic droit sur la coque |
| Carte du système | M |
| Recul de la caméra | molette |

### Systèmes

- **Assistance de vol** (par défaut) : les commandes donnent une vitesse ; manche au neutre, le vaisseau freine et
  tient le stationnaire, gravité compensée. **Découplé** : poussée directe, inertie conservée (dérive dans l'espace).
- **Hydrogène** : consommé en proportion de la poussée, ×2,5 en postcombustion. Réservoir vide : plus de poussée ni
  de gouverne, le vaisseau tombe (sur une planète) ou dérive (dans l'espace).
- **Bouclier** : absorbe les dégâts, se recharge après quelques secondes sans impact (bulle visible quand il encaisse).
- **Coque** : entamée par les tirs et les chocs au-delà de 8 m/s ; à zéro, le vaisseau explose et éjecte l'équipage.
- **Condensateur** : énergie des canons.
- **Répartition d'énergie** : équilibrée, boucliers (recharge ×1,9), armes (énergie ×1,8) ou moteurs (vitesse ×1,2).
- **Cabine pressurisée** : on respire à bord tant que la coque tient.
- **Soute** : 54 emplacements, sauvegardés avec le vaisseau, répandus si le vaisseau est détruit. Un vaisseau ne se
  remballe que soute vide.
- **Armes** : canons laser alternés, convergents vers le point visé (cône de 25°) ; missiles guidés vers la cible
  verrouillée.
- **Collisions** : 640 points de la peau de la coque sont testés contre le relief, axe par axe ; une rotation qui
  ferait pénétrer le terrain est annulée.

Le client du pilote a l'autorité sur le mouvement (comme pour les bateaux vanilla) et l'envoie à chaque tick ; le
serveur garde celle des ressources, des armes, des dégâts et des sauts, et contrôle la vraisemblance des positions.

### Ajouter un vaisseau

Déposer `data/<ns>/ships/<id>.json` et la schématique `data/<ns>/ships/<nom>.schem` :

```json
{
  "schematic": "<ns>:<nom>",
  "forward": "+x",
  "camera_distance": 30,
  "seats": [[x, y, z], ...],
  "engines": [[x, y, z], ...],
  "guns": [[x, y, z], ...],
  "missiles": [[x, y, z], ...],
  "hostile": false,
  "stats": { "hull": 3000, "shield": 1800, "max_speed": 210, ... }
}
```

Les points sont en coordonnées de la schématique (avant rotation) ; `forward` indique où pointe le nez (`+x`, `-x`,
`+z`, `-z`). Le premier siège est celui du pilote. Statistiques disponibles : voir `ShipDesign.Stats`.

## 2. Espace

La dimension `odyssea:space` contient tout le système solaire, comprimé : un bloc vaut un kilomètre visuel, le Soleil
est à l'origine, la Terre à 100 000 blocs, Neptune à 820 000. Les rayons sont divisés par deux. Le plan de
l'écliptique est à Y = 1 000 ; on peut s'en écarter de 1 000 blocs de part et d'autre.

- **Quitter une planète** : monter au-dessus de Y = 1 960 (en vaisseau, en jetpack ou en mode créatif). On arrive
  au-dessus du même point de la sphère, avec la même orientation et la même vitesse.
- **Se poser** : franchir le sommet de l'atmosphère d'une planète (Mercure, Vénus, Terra, Lune, Mars, Jupiter,
  Saturne, Uranus, Neptune). On arrive à Y = 1 850 au-dessus du point survolé : longitude → X, latitude → Z
  (rayon de la sphère = 1 bloc de surface par radian et par bloc de rayon). Les autres lunes sont visibles mais leur
  surface est inaccessible.
- **Ciel** : chaque astre est dessiné à sa vraie position avec son atmosphère et ses anneaux ; le diamètre et
  l'éclairement du Soleil varient avec la distance (Mercure brûlant, Neptune dans la pénombre).
- **Astéroïdes** : ceinture dense entre 200 000 et 250 000 blocs (roches, basaltes, astéroïdes métalliques à fer,
  or, diamant et débris antiques), anneaux de Saturne faits de blocs de glace, corps glacés au-delà de 560 000 blocs.
- **Danger solaire** : la coque chauffe à moins de 6 000 blocs du Soleil.
- **Pirates** : des chasseurs pirates surgissent parfois autour des vaisseaux habités (plus souvent dans la ceinture).
  Ils lâchent hydrogène, carburant quantique, métaux et missiles. Règle de jeu `/gamerule odysseaPirates false` pour
  les désactiver.

### Saut quantique

1. Ouvrir la carte (M), choisir un astre, « Définir comme destination ».
2. Dans l'espace, hors du puits de gravité des astres, aligner le vaisseau sur le repère violet de l'affichage.
3. Appuyer sur J : 3 s de mise en route (garder l'alignement), puis 5 à 45 s de trajet, le ciel défilant.
4. Arrivée à 1 400 blocs au-dessus de l'atmosphère, nez vers l'astre.

Coût : 1 unité de carburant quantique par 10 000 blocs (Terre → Mars ≈ 21, Terre → Neptune ≈ 90).

## 3. Carte du système (M)

Orbites, planètes, lunes, ceinture, position du joueur (dans l'espace : réelle ; sur une planète : celle de la
planète) et route vers la destination. Glisser pour se déplacer, molette pour zoomer autour du curseur, clic pour
sélectionner (distance, gravité, atmosphère, temps et coût du saut), double-clic pour centrer et zoomer.

## 4. Combinaison spatiale

Casque, combinaison, jambières, bottes (tag `#odyssea:pressure_suit`).

- La combinaison complète rend étanche : on respire dans le vide et les atmosphères toxiques.
- Réserve d'oxygène de 5 minutes dans le torse (barre bleue de l'objet, jauge à l'écran) ; elle se remplit dans un
  air respirable, une salle pressurisée ou une cabine de vaisseau. Vide : asphyxie.
- Dans l'espace, la combinaison complète donne le vol libre (propulseurs de manœuvre).
- Gravité de chaque corps (attribut `gravity`) : on bondit sur la Lune, on flotte dans l'espace ; dégâts de chute et
  hauteur de saut ajustés (au plus 2,5 fois la hauteur terrestre).
- Rentrée à pied depuis l'espace : arrivée 40 blocs au-dessus du sol avec 30 s de chute ralentie.

## 5. Horizon de Terra

Au-delà de la distance de rendu, le ciel dessinait un sol vert uniforme. Le serveur échantillonne désormais, sans
générer de tronçon, le relief et les biomes que produirait le générateur sur quatre anneaux (320 à 2 400 blocs) dans
32 directions (`horizon.HorizonSampler`, fil dédié). Le shader céleste en tire :

- la **silhouette des reliefs lointains** (montagnes à l'horizon, interpolées et légèrement dentelées) ;
- la **couleur du sol** dans chaque direction : océans sombres, plages et déserts, badlands ocre, neige au-dessus de
  la limite climatique, roche des pics, teinte d'herbe et de feuillage des autres biomes.

Le calcul est renouvelé tous les 96 blocs parcourus ou toutes les 30 secondes.

## 6. Test automatique

`./gradlew runSmokeClient --offline "-PsmokeTour=ships"` : horizon de Terra, déploiement d'un Cutlass, vol, tir,
montée jusqu'à l'espace, vue de la Terre, saut quantique vers Mars, combat contre deux pirates, Destroyer, entrée dans
l'atmosphère de Mars. Captures dans `run/screenshots/odyssea_ships_*.png`.
