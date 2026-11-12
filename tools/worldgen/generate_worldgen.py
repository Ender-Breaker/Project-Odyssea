"""Génère tout le JSON de génération de monde d'Odyssea dans src/main/resources/data.

    python tools/worldgen/generate_worldgen.py

Les dossiers listés dans GENERATED_DIRS sont entièrement réécrits : ne pas y éditer de fichier à la main,
modifier plutôt terra.py (overworld) ou planets.py (planètes) puis relancer ce script.
"""

import json
import shutil
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import planets  # noqa: E402
import terra  # noqa: E402

DATA = Path(__file__).resolve().parents[2] / "src" / "main" / "resources" / "data"
GENERATED_DIRS = [
    "odyssea/worldgen/noise",
    "odyssea/worldgen/density_function",
    "odyssea/worldgen/noise_settings",
    "odyssea/worldgen/biome",
    "odyssea/dimension",
    "odyssea/dimension_type",
    "minecraft/dimension",
    "minecraft/dimension_type",
]

written = 0


def write(relative, content):
    global written
    path = DATA / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="\n") as f:
        json.dump(content, f, indent=2, ensure_ascii=False)
        f.write("\n")
    written += 1


def main():
    for directory in GENERATED_DIRS:
        shutil.rmtree(DATA / directory, ignore_errors=True)

    for name, noise in {**planets.shared_noises(), **terra.noises()}.items():
        write(f"odyssea/worldgen/noise/{name}.json", noise)

    # Terra (overworld)
    for name, df in terra.density_functions().items():
        write(f"odyssea/worldgen/density_function/{name}.json", df)
    write("odyssea/worldgen/noise_settings/terra.json", terra.noise_settings())
    write("minecraft/dimension_type/overworld.json", terra.dimension_type())
    write("minecraft/dimension/overworld.json", terra.dimension())

    # Corps rocheux
    for factory in (planets.luna, planets.mercury, planets.venus, planets.mars, planets.uranus):
        planet, settings, dimension_type = factory()
        for name, df in planet.dfs.items():
            write(f"odyssea/worldgen/density_function/{name}.json", df)
        for name, biome in planet.biomes.items():
            write(f"odyssea/worldgen/biome/{name}.json", biome)
        write(f"odyssea/worldgen/noise_settings/{planet.name}.json", settings)
        write(f"odyssea/dimension_type/{planet.name}.json", dimension_type)
        write(f"odyssea/dimension/{planet.name}.json", planets.noise_dimension(planet))

    # Géantes gazeuses
    for name, biomes, dimension, dimension_type in planets.gas_giants():
        for biome_name, biome in biomes.items():
            write(f"odyssea/worldgen/biome/{biome_name}.json", biome)
        write(f"odyssea/dimension_type/{name}.json", dimension_type)
        write(f"odyssea/dimension/{name}.json", dimension)

    # Espace
    biome, settings, dimension, dimension_type, configured, placed = planets.space()
    write("odyssea/worldgen/biome/space.json", biome)
    write("odyssea/worldgen/noise_settings/space.json", settings)
    write("odyssea/dimension_type/space.json", dimension_type)
    write("odyssea/dimension/space.json", dimension)
    write("odyssea/worldgen/configured_feature/asteroid_field.json", configured)
    write("odyssea/worldgen/placed_feature/asteroid_field.json", placed)

    print(f"{written} fichiers écrits dans {DATA}")


if __name__ == "__main__":
    main()
