package dev.stannismod.stellurgy.affs.client;

import dev.stannismod.stellurgy.affs.world.FieldFrame;
import dev.stannismod.stellurgy.affs.world.FieldFrames;
import dev.stannismod.stellurgy.affs.world.FieldSource;
import dev.stannismod.stellurgy.affs.world.FieldSurfaceMath;
import dev.stannismod.stellurgy.affs.world.ShipFieldFrame;
import dev.stannismod.stellurgy.integration.vs.VSIntegration;
import dev.stannismod.stellurgy.world.WorldRuntime;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ClientForceFieldRenderCache {

    /** The active emitters a client world was last told about, as that world's own part
     *  ({@link WorldRuntime}): it goes with the world, so nothing of one world is drawn in the next. */
    private static final class Snapshot {
        volatile RenderState state;
    }

    private ClientForceFieldRenderCache() {
    }

    /**
     * Take the server's emitter snapshot for {@code dimension}, if that is the world this client shows.
     * The world is looked up here, inside a client class, because the packet handler that calls this
     * is common code and must not name the client's world type.
     */
    public static void replaceSnapshot(int dimension, List<? extends FieldSource> sources) {
        World world = net.minecraft.client.Minecraft.getMinecraft().world;
        if (world == null || world.provider.getDimension() != dimension) {
            return;
        }
        WorldRuntime.of(world, Snapshot.class, Snapshot::new).state = new RenderState(sources);
    }

    public static RenderMesh getMesh(World world) {
        if (world == null) {
            return RenderMesh.EMPTY;
        }

        RenderState state = WorldRuntime.of(world, Snapshot.class, Snapshot::new).state;
        if (state == null) {
            return RenderMesh.EMPTY;
        }
        return state.getMesh(world);
    }

    private static final class RenderState {
        private final List<FieldSource> sources;
        /** The standalone fields' mesh, in world coordinates; built on the first draw. */
        private RenderMesh standalone;
        /**
         * Each ship's fields meshed once in the ship's OWN frame (its subspace), where they never move,
         * and carried to the world every draw by the ship's render pose. Meshed in the world instead,
         * the grid was laid on the world lattice round a centre that moves every frame, so the triangles
         * changed from frame to frame and the shell shimmered; and the tick pose it was placed by is not
         * the interpolated pose the hull is drawn at, so it also lagged the hull.
         */
        private final java.util.Map<String, RenderMesh> shipMeshes = new java.util.HashMap<>();

        private RenderState(List<? extends FieldSource> sources) {
            this.sources = Collections.unmodifiableList(new ArrayList<>(sources));
        }

        private RenderMesh getMesh(World world) {
            // The frames are resolved here because the client world is only available at draw time.
            List<FieldSource> worldSources = new ArrayList<>();
            java.util.Map<String, List<FieldSource>> shipSources = new java.util.LinkedHashMap<>();
            List<FieldSource> unnamed = new ArrayList<>();
            for (FieldSource s : sources) {
                FieldFrame frame = FieldFrames.forBlock(world, s.getPos());
                if (frame instanceof ShipFieldFrame) {
                    shipSources.computeIfAbsent(((ShipFieldFrame) frame).getShipId(), k -> new ArrayList<>()).add(s);
                } else if (frame instanceof dev.stannismod.stellurgy.affs.world.WorldFieldFrame) {
                    worldSources.add(s);
                } else {
                    unnamed.add(new FramedSource(s, frame));
                }
            }

            if (standalone == null) {
                standalone = buildMesh(worldSources);
            }
            List<Triangle> triangles = new ArrayList<>(standalone.getTriangles());
            for (java.util.Map.Entry<String, List<FieldSource>> ship : shipSources.entrySet()) {
                // A snapshot source's own centre is its block centre — on a ship, the subspace one.
                RenderMesh local = shipMeshes.computeIfAbsent(ship.getKey(), k -> buildMesh(ship.getValue()));
                RenderPose pose = RenderPose.of(world, ship.getKey(), ship.getValue().get(0).getWorldCenter());
                if (pose == null) {
                    continue;
                }
                for (Triangle t : local.getTriangles()) {
                    triangles.add(new Triangle(pose.apply(t.a), pose.apply(t.b), pose.apply(t.c)));
                }
            }
            // A field on a ship that has no name yet has no pose to be carried by; it is meshed where it is.
            triangles.addAll(buildMesh(unnamed).getTriangles());
            return new RenderMesh(triangles);
        }
    }

    /**
     * A ship's render pose as an affine map from its subspace to the world, taken once per draw: the
     * image of a reference point and of the three unit steps from it. Taken about a point among the
     * fields rather than at the subspace origin, whose coordinates are millions of blocks out and would
     * cost the steps their precision.
     */
    private static final class RenderPose {
        private final Vec3d ref;
        private final Vec3d at;
        private final Vec3d ex;
        private final Vec3d ey;
        private final Vec3d ez;

        private RenderPose(Vec3d ref, Vec3d at, Vec3d ex, Vec3d ey, Vec3d ez) {
            this.ref = ref;
            this.at = at;
            this.ex = ex;
            this.ey = ey;
            this.ez = ez;
        }

        static RenderPose of(World world, String shipId, Vec3d ref) {
            double[] o = VSIntegration.renderToWorldFrameFor(world, shipId, ref.x, ref.y, ref.z);
            double[] x = VSIntegration.renderToWorldFrameFor(world, shipId, ref.x + 1.0D, ref.y, ref.z);
            double[] y = VSIntegration.renderToWorldFrameFor(world, shipId, ref.x, ref.y + 1.0D, ref.z);
            double[] z = VSIntegration.renderToWorldFrameFor(world, shipId, ref.x, ref.y, ref.z + 1.0D);
            if (o == null || x == null || y == null || z == null) {
                return null;
            }
            Vec3d at = new Vec3d(o[0], o[1], o[2]);
            return new RenderPose(ref, at, new Vec3d(x[0], x[1], x[2]).subtract(at),
                    new Vec3d(y[0], y[1], y[2]).subtract(at), new Vec3d(z[0], z[1], z[2]).subtract(at));
        }

        Vec3d apply(Vec3d p) {
            double dx = p.x - ref.x;
            double dy = p.y - ref.y;
            double dz = p.z - ref.z;
            return new Vec3d(at.x + ex.x * dx + ey.x * dy + ez.x * dz,
                    at.y + ex.y * dx + ey.y * dy + ez.y * dz,
                    at.z + ex.z * dx + ey.z * dy + ez.z * dz);
        }
    }

    /** A client-side source that reports its world centre through a resolved frame (identity standalone,
     *  ship-transformed on a VS hull), so the render mesh follows the flying hull.
     *
     *  <p>The centre is resolved once, when the source is built for a draw: the mesh asks for it at every
     *  corner of every grid cell, and through a ship's frame each ask is a lookup of the ship and a pose
     *  transform — measured 2026-10-28 at about 70 000 a frame for Ship 1's two emitters, 6 FPS aboard.</p> */
    private static final class FramedSource implements FieldSource {
        private final FieldSource delegate;
        private final Vec3d center;

        private FramedSource(FieldSource delegate, FieldFrame frame) {
            this.delegate = delegate;
            net.minecraft.util.math.BlockPos pos = delegate.getPos();
            Vec3d c = frame.fieldToWorld(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
            this.center = c != null ? c : new Vec3d(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
        }

        @Override
        public net.minecraft.util.math.BlockPos getPos() {
            return delegate.getPos();
        }

        @Override
        public int getRadius() {
            return delegate.getRadius();
        }

        @Override
        public Vec3d getWorldCenter() {
            return center;
        }
    }

    private static RenderMesh buildMesh(List<FieldSource> sources) {
        if (sources.isEmpty()) {
            return RenderMesh.EMPTY;
        }

        SetBuilder builder = new SetBuilder();
        for (FieldSource source : sources) {
            // Lay the marching-cubes grid around the field's WORLD centre (identity standalone,
            // hull-transformed on a ship), on the half-unit lattice the sampler expects, so the grid and
            // the SDF — which also reads getWorldCenter — stay consistent in both frames.
            Vec3d wc = source.getWorldCenter();
            int cx = (int) Math.floor(wc.x * 2.0D);
            int cy = (int) Math.floor(wc.y * 2.0D);
            int cz = (int) Math.floor(wc.z * 2.0D);
            // Every half-unit cell of the box, in half-units: a cell spans 0.5, so stepping by two of them
            // sampled one cell in eight and left the shell full of holes.
            int halfUnits = (source.getRadius() + 2) * 2;
            for (int dx = -halfUnits; dx <= halfUnits; dx++) {
                for (int dy = -halfUnits; dy <= halfUnits; dy++) {
                    for (int dz = -halfUnits; dz <= halfUnits; dz++) {
                        builder.add(new GridCell(cx + dx, cy + dy, cz + dz));
                    }
                }
            }
        }

        List<Triangle> triangles = new ArrayList<>();
        for (GridCell cell : builder.values()) {
            addCellTriangles(triangles, sources, cell);
        }
        return new RenderMesh(Collections.unmodifiableList(triangles));
    }

    private static void addCellTriangles(List<Triangle> triangles, List<FieldSource> sources, GridCell cell) {
        double x0 = cell.x * 0.5D;
        double y0 = cell.y * 0.5D;
        double z0 = cell.z * 0.5D;
        double x1 = x0 + 0.5D;
        double y1 = y0 + 0.5D;
        double z1 = z0 + 0.5D;

        Vec3d[] positions = new Vec3d[] {
            new Vec3d(x0, y0, z0),
            new Vec3d(x1, y0, z0),
            new Vec3d(x1, y1, z0),
            new Vec3d(x0, y1, z0),
            new Vec3d(x0, y0, z1),
            new Vec3d(x1, y0, z1),
            new Vec3d(x1, y1, z1),
            new Vec3d(x0, y1, z1)
        };

        double[] values = new double[8];
        boolean hasInside = false;
        boolean hasOutside = false;
        for (int i = 0; i < 8; i++) {
            values[i] = FieldSurfaceMath.compositeHullDistance(sources, positions[i]);
            if (values[i] <= 0.0D) {
                hasInside = true;
            } else {
                hasOutside = true;
            }
        }

        if (!hasInside || !hasOutside) {
            return;
        }

        int[][] tets = {
            {0, 5, 1, 6},
            {0, 1, 2, 6},
            {0, 2, 3, 6},
            {0, 3, 7, 6},
            {0, 7, 4, 6},
            {0, 4, 5, 6}
        };

        for (int[] tet : tets) {
            emitTetra(triangles, positions, values, tet);
        }
    }

    private static void emitTetra(List<Triangle> triangles, Vec3d[] positions, double[] values, int[] tet) {
        Vec3d[] verts = new Vec3d[4];
        double[] vals = new double[4];
        for (int i = 0; i < 4; i++) {
            verts[i] = positions[tet[i]];
            vals[i] = values[tet[i]];
        }

        int[] inside = new int[4];
        int[] outside = new int[4];
        int insideCount = 0;
        int outsideCount = 0;

        for (int i = 0; i < 4; i++) {
            if (vals[i] <= 0.0D) {
                inside[insideCount++] = i;
            } else {
                outside[outsideCount++] = i;
            }
        }

        if (insideCount == 0 || insideCount == 4) {
            return;
        }

        if (insideCount == 1 || insideCount == 3) {
            int pivot = insideCount == 1 ? inside[0] : outside[0];
            int a = insideCount == 1 ? outside[0] : inside[0];
            int b = insideCount == 1 ? outside[1] : inside[1];
            int c = insideCount == 1 ? outside[2] : inside[2];

            Vec3d p0 = interpolate(verts[pivot], vals[pivot], verts[a], vals[a]);
            Vec3d p1 = interpolate(verts[pivot], vals[pivot], verts[b], vals[b]);
            Vec3d p2 = interpolate(verts[pivot], vals[pivot], verts[c], vals[c]);

            if (insideCount == 1) {
                triangles.add(new Triangle(p0, p1, p2));
            } else {
                triangles.add(new Triangle(p0, p2, p1));
            }
            return;
        }

        if (insideCount == 2) {
            int in0 = inside[0];
            int in1 = inside[1];
            int out0 = outside[0];
            int out1 = outside[1];

            Vec3d p0 = interpolate(verts[in0], vals[in0], verts[out0], vals[out0]);
            Vec3d p1 = interpolate(verts[in0], vals[in0], verts[out1], vals[out1]);
            Vec3d p2 = interpolate(verts[in1], vals[in1], verts[out0], vals[out0]);
            Vec3d p3 = interpolate(verts[in1], vals[in1], verts[out1], vals[out1]);

            triangles.add(new Triangle(p0, p1, p2));
            triangles.add(new Triangle(p2, p1, p3));
        }
    }

    private static Vec3d interpolate(Vec3d a, double va, Vec3d b, double vb) {
        double t = va / (va - vb);
        return new Vec3d(
            a.x + (b.x - a.x) * t,
            a.y + (b.y - a.y) * t,
            a.z + (b.z - a.z) * t
        );
    }

    public static final class RenderMesh {
        /** Effectively final, process lifetime: built once at class initialisation. */
        public static final RenderMesh EMPTY = new RenderMesh(Collections.emptyList());
        private final List<Triangle> triangles;

        private RenderMesh(List<Triangle> triangles) {
            this.triangles = triangles;
        }

        public List<Triangle> getTriangles() {
            return triangles;
        }
    }

    public static final class Triangle {
        public final Vec3d a;
        public final Vec3d b;
        public final Vec3d c;

        private Triangle(Vec3d a, Vec3d b, Vec3d c) {
            this.a = a;
            this.b = b;
            this.c = c;
        }
    }

    private static final class GridCell {
        private final int x;
        private final int y;
        private final int z;

        private GridCell(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof GridCell)) {
                return false;
            }
            GridCell other = (GridCell) obj;
            return x == other.x && y == other.y && z == other.z;
        }

        @Override
        public int hashCode() {
            int result = Integer.hashCode(x);
            result = 31 * result + Integer.hashCode(y);
            result = 31 * result + Integer.hashCode(z);
            return result;
        }
    }

    private static final class SetBuilder {
        private final java.util.HashSet<GridCell> cells = new java.util.HashSet<>();

        private void add(GridCell cell) {
            cells.add(cell);
        }

        private java.util.Set<GridCell> values() {
            return cells;
        }
    }
}
