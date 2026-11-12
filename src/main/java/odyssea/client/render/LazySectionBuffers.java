package odyssea.client.render;

import com.mojang.blaze3d.vertex.VertexBuffer;
import net.minecraft.client.renderer.RenderType;

/**
 * Tampons GPU paresseux pour les sections de rendu du terrain.
 *
 * <p>Vanilla 1.21.1 crée, pour chaque section de 16³ de la zone de rendu, 5 {@link VertexBuffer} (un par couche)
 * de 3 objets OpenGL chacun, dès la construction de la zone. Avec une dimension de 2288 blocs de haut (143 sections)
 * et une distance de rendu de 32, cela fait ~604 000 sections et ~9 millions d'objets OpenGL créés d'un coup sur le
 * thread de rendu : le jeu reste figé sur l'écran de chargement.
 *
 * <p>Ici, une section ne crée un tampon que pour une couche qui contient réellement de la géométrie, et les objets
 * OpenGL ne sont générés qu'au premier {@code bind()} (toujours sur le thread de rendu). Les sections vides — le ciel,
 * l'essentiel des 2000 blocs au-dessus du relief — ne coûtent plus rien côté GPU.
 */
public final class LazySectionBuffers {
    private static final ThreadLocal<Boolean> CREATING = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private LazySectionBuffers() {
    }

    /** Vrai pendant la construction d'un tampon paresseux sur le thread courant (lu par le mixin de VertexBuffer). */
    public static boolean isCreatingLazy() {
        return CREATING.get();
    }

    /** Crée un tampon sans objet OpenGL : peut être appelé depuis un thread de compilation des sections. */
    public static VertexBuffer create(RenderType layer) {
        CREATING.set(Boolean.TRUE);
        try {
            return new VertexBuffer(VertexBuffer.Usage.STATIC);
        } finally {
            CREATING.set(Boolean.FALSE);
        }
    }
}
