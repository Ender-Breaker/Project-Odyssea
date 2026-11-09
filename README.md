# Project Odyssea

**Modpack Minecraft spatial hardcore** et son **mod Core** dédié : construire un vaisseau bloc par bloc, le piloter avec une physique newtonienne, et survivre dans le vide.

> État : phase de conception initiale. Le dépôt ne contient pas encore de code jouable.

## Concept

Odyssea transforme Minecraft en simulation de survie spatiale. Le joueur ne pilote pas un véhicule préfabriqué : il conçoit, assemble et entretient son propre vaisseau, avec les contraintes que cela implique (étanchéité, énergie, fluides, dégâts).

## Piliers de gameplay

### Vaisseaux modulaires
- Construction libre en blocs : la forme, la masse et la répartition des modules sont à la charge du joueur.
- Physique hybride : inertie newtonienne complète, avec un **flight assist** activable qui amortit et stabilise les mouvements.
- Système de **bouclier et de dégâts** : l'énergie absorbée, la répartition des impacts et l'intégrité structurelle dépendent de la conception du vaisseau.

### Systèmes embarqués
- **Pressurisation** : détection des volumes étanches par flood-fill optimisé ; une brèche dépressurise la zone concernée.
- **Fluides et câbles** : réseaux de transport (énergie, fluides) modélisés sous forme de graphes.

### Survie hardcore
- **EVA** : sorties extravéhiculaires avec gestion de l'oxygène et de la propulsion.
- **Asphyxie** en cas de manque d'oxygène ou de dépressurisation.
- **Sons étouffés** dans le vide : le son ne se propage que par les structures.

### Rendu spatial
- Shaders GLSL : raymarching, atmosphères planétaires par diffusion **Rayleigh/Mie**.
- Intégration avec **Veil** et **Iris**.

## Principes techniques

- **Java 21**, ciblant Fabric / NeoForge.
- Architecture modulaire, orientée **multijoueur** : pas de calculs lourds par tick, structures en graphes, traitements asynchrones lorsque c'est possible.
- Rendu côté client isolé de la logique serveur ; seules les données nécessaires sont synchronisées sur le réseau.

## Structure du dépôt

| Élément | Rôle |
|---|---|
| `README.md` | Présentation du projet |

La structure s'enrichira avec le mod Core (code Java, ressources, shaders GLSL) et la configuration du modpack.

## Feuille de route

1. Mod Core : structure du projet, système de vaisseaux en blocs, physique hybride.
2. Pressurisation et réseaux de fluides / câbles.
3. Survie hardcore : EVA, asphyxie, ambiance sonore.
4. Rendu spatial : shaders, atmosphères.
5. Assemblage du modpack et équilibrage.

## Licence

À définir.
