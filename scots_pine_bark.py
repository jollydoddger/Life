"""
Procedural Scots Pine Bark — Blender Python Script
====================================================
Run in Blender's Text Editor (or paste into the scripting workspace).

Creates a realistic Scots pine bark displacement on the active mesh object.
Displacement is applied along vertex normals so it works on any face
orientation — flat panels, curved surfaces, corners.

Designed for 3D-printed ASA parts that will be sanded, primed and 2K coated.
Default depth values include ~0.8 mm compensation for coating loss.

Algorithm: multi-layer stretched Voronoi (F2−F1) with discrete plate heights,
coordinate warp, sub-plates, horizontal breaks, per-plate crumple, tilt,
fibre grain, and micro roughness.
"""

import bpy
import bmesh
import math
import random
from mathutils import Vector, noise

# ---------------------------------------------------------------------------
# Utility: hashed Perlin helpers using Blender's mathutils.noise
# ---------------------------------------------------------------------------

def perlin(x, y, z):
    """Wrapper around Blender's noise.noise (returns −1…1)."""
    return noise.noise(Vector((x, y, z)))


def fbm(x, y, z, octaves=4, lacunarity=2.0, gain=0.5):
    """Fractal Brownian Motion built on Blender Perlin."""
    total = 0.0
    amp = 1.0
    freq = 1.0
    for _ in range(octaves):
        total += perlin(x * freq, y * freq, z * freq) * amp
        freq *= lacunarity
        amp *= gain
    return total


# ---------------------------------------------------------------------------
# Voronoi F1 / F2 (brute 3×3×3 grid search — plenty fast for vertex counts)
# ---------------------------------------------------------------------------

def _hash_vec(ix, iy, iz, seed):
    """Deterministic pseudo-random point inside a grid cell."""
    # Use three different prime offsets per axis for x/y/z of the point.
    n = ix * 374761393 + iy * 668265263 + iz * 1274126177 + seed * 1013904223
    n = (n ^ (n >> 13)) * 1274126177
    n = n & 0x7FFFFFFF
    fx = (n & 0xFFFF) / 65535.0
    n = (n * 1664525 + 1013904223) & 0x7FFFFFFF
    fy = (n & 0xFFFF) / 65535.0
    n = (n * 1664525 + 1013904223) & 0x7FFFFFFF
    fz = (n & 0xFFFF) / 65535.0
    return (ix + fx, iy + fy, iz + fz)


def _cell_hash(ix, iy, iz, seed):
    """Deterministic per-cell scalar hash in 0…1."""
    n = ix * 374761393 + iy * 668265263 + iz * 1274126177 + seed * 982451653
    n = (n ^ (n >> 13)) * 1274126177
    n = n & 0x7FFFFFFF
    return (n & 0xFFFF) / 65535.0


def voronoi_f1_f2(px, py, pz, seed=0):
    """Return (f1, f2, cell_hash) where f1/f2 are distances to nearest /
    second-nearest Voronoi site, and cell_hash is the hash of the nearest cell."""
    ix0 = math.floor(px)
    iy0 = math.floor(py)
    iz0 = math.floor(pz)

    d1 = 1e10
    d2 = 1e10
    best_hash = 0.0

    for dz in range(-1, 2):
        for dy in range(-1, 2):
            for dx in range(-1, 2):
                cx, cy, cz = ix0 + dx, iy0 + dy, iz0 + dz
                sx, sy, sz = _hash_vec(cx, cy, cz, seed)
                dd = (px - sx) ** 2 + (py - sy) ** 2 + (pz - sz) ** 2
                if dd < d1:
                    d2 = d1
                    d1 = dd
                    best_hash = _cell_hash(cx, cy, cz, seed)
                elif dd < d2:
                    d2 = dd

    return math.sqrt(d1), math.sqrt(d2), best_hash


# ---------------------------------------------------------------------------
# Smoothstep
# ---------------------------------------------------------------------------

def smoothstep(edge0, edge1, x):
    t = max(0.0, min(1.0, (x - edge0) / (edge1 - edge0 + 1e-9)))
    return t * t * (3.0 - 2.0 * t)


# ---------------------------------------------------------------------------
# Main displacement function
# ---------------------------------------------------------------------------

def bark_displacement(pos, normal, params):
    """Compute scalar displacement for one vertex.

    *pos*: mathutils.Vector — object-space position
    *normal*: mathutils.Vector — vertex normal (unit)
    *params*: dict of parameters (see DEFAULT_PARAMS)

    Returns a float (mm displacement along normal).
    """
    p = params
    seed = p["seed"]

    x, y, z = pos.x, pos.y, pos.z

    # ---- Layer 0: coordinate warp ----
    warp_freq = p["warp_freq"]
    wx = fbm(x * warp_freq, y * warp_freq, z * warp_freq, octaves=3) * p["warp_x"]
    wy = fbm(
        x * warp_freq + 31.7,
        y * warp_freq + 31.7,
        z * warp_freq + 31.7,
        octaves=3,
    ) * p["warp_y"]
    wz = fbm(
        x * warp_freq + 67.3,
        y * warp_freq + 67.3,
        z * warp_freq + 67.3,
        octaves=3,
    ) * p["warp_z"]
    x += wx
    y += wy
    z += wz

    # ---- Layer 1: macro plates (Voronoi F2−F1) ----
    ps = p["plate_scale"]
    ys = p["y_stretch"]
    vx = x * ps
    vy = y * ps / ys
    vz = z * ps

    f1, f2, ch = voronoi_f1_f2(vx, vy, vz, seed=seed)
    boundary = f2 - f1  # 0 at boundary, positive on plate

    # Edge fray: perturb boundary distance with high-freq noise
    fray_n = fbm(
        x * ps * 4.0 + 100.0,
        y * ps * 4.0 / ys + 100.0,
        z * ps * 4.0 + 100.0,
        octaves=3,
    )
    boundary += fray_n * p["edge_fray"]

    # Wall profile: sharp transition
    wall = smoothstep(0.0, p["wall_steepness"], boundary)

    # Discrete height level for this plate
    num_levels = p["num_levels"]
    level = math.floor(ch * num_levels * 0.999)
    level_h = (level / max(num_levels - 1, 1)) * p["layer_depth"]
    macro_h = wall * level_h

    # ---- Layer 5 (applied early): plate tilt ----
    tilt_dir_x = ch * 2.0 - 1.0
    tilt_dir_y = _cell_hash(
        math.floor(vx), math.floor(vy), math.floor(vz), seed + 77
    ) * 2.0 - 1.0
    tilt_h = wall * p["plate_tilt"] * (
        tilt_dir_x * (pos.x * ps - math.floor(vx))
        + tilt_dir_y * (pos.y * ps / ys - math.floor(vy))
    )

    # ---- Layer 2: sub-plates ----
    ss = p["sub_scale"]
    svx = x * ps * ss
    svy = y * ps * ss / (ys * 0.6)
    svz = z * ps * ss
    sf1, sf2, sch = voronoi_f1_f2(svx, svy, svz, seed=seed + 42)
    s_boundary = sf2 - sf1
    s_boundary += fbm(
        x * ps * ss * 3.0 + 200.0,
        y * ps * ss * 3.0 / ys + 200.0,
        z * ps * ss * 3.0 + 200.0,
        octaves=2,
    ) * p["edge_fray"] * 0.5
    s_wall = smoothstep(0.0, p["wall_steepness"] * 1.5, s_boundary)
    s_level = math.floor(sch * 2.0 * 0.999)
    sub_h = wall * s_wall * s_level * p["sub_depth"]

    # ---- Layer 3: horizontal breaks ----
    hb_freq = p["hbreak_freq"]
    hb_y = y * hb_freq + fbm(x * 0.5 + 300.0, y * 0.5 + 300.0, z * 0.5 + 300.0, octaves=2) * 0.4
    hb_val = math.sin(hb_y * math.pi * 2.0) * 0.5 + 0.5  # 0…1
    hb_cut = 1.0 - smoothstep(0.0, 0.08, hb_val)  # narrow cuts
    hbreak_h = -wall * hb_cut * p["hbreak_depth"]

    # ---- Layer 4: per-plate crumple ----
    crumple_intensity = 0.3 + ch * p["per_plate_var"] * 0.7
    crumple_h = wall * crumple_intensity * fbm(
        x * ps * 2.5 + 400.0,
        y * ps * 1.2 + 400.0,
        z * ps * 2.5 + 400.0,
        octaves=3,
    ) * p["crumple"]

    # ---- Layer 6: vertical fibre grain ----
    fibre_h = fbm(
        x * ps * 6.0 + 500.0,
        y * ps * 0.3 + 500.0,
        z * ps * 6.0 + 500.0,
        octaves=2,
    ) * p["fibre_grain"]

    # ---- Layer 7: micro roughness ----
    micro_h = fbm(
        x * ps * 12.0 + 600.0,
        y * ps * 12.0 + 600.0,
        z * ps * 12.0 + 600.0,
        octaves=2,
    ) * p["micro_roughness"]

    # ---- combine ----
    total = macro_h + tilt_h + sub_h + hbreak_h + crumple_h + fibre_h + micro_h
    return total * p["overall_depth"]


# ---------------------------------------------------------------------------
# Default parameters (units: mm, working in Blender-metres → scale later)
# ---------------------------------------------------------------------------

DEFAULT_PARAMS = {
    "seed": 0,
    # coordinate warp
    "warp_freq": 0.25,
    "warp_x": 1.2,
    "warp_y": 0.15,
    "warp_z": 0.6,
    # macro plates
    "plate_scale": 0.8,          # plates per ~unit; tune to mesh size
    "y_stretch": 8.0,            # vertical elongation
    "num_levels": 3,             # distinct height levels (2–4)
    "layer_depth": 1.0,          # depth between lowest and highest level (relative)
    "wall_steepness": 0.07,      # lower = sharper cliffs (0.02–0.15)
    "edge_fray": 0.06,           # organic irregularity at plate edges
    # sub-plates
    "sub_scale": 2.5,            # relative to macro
    "sub_depth": 0.15,           # relative depth
    # horizontal breaks
    "hbreak_freq": 3.0,          # breaks per unit height
    "hbreak_depth": 0.25,        # cut depth (relative)
    # crumple
    "crumple": 0.12,             # plate surface texture
    "per_plate_var": 0.8,        # 0–1 variation in crumple intensity
    # tilt
    "plate_tilt": 0.08,          # peeling/lifting effect
    # fibre grain
    "fibre_grain": 0.03,         # fine vertical striations
    # micro roughness
    "micro_roughness": 0.02,     # high-freq surface detail
    # master scale
    "overall_depth": 3.0,        # mm — includes ~0.8 mm coating compensation
}


# ---------------------------------------------------------------------------
# Operator: apply bark displacement to selected mesh
# ---------------------------------------------------------------------------

class MESH_OT_scots_pine_bark(bpy.types.Operator):
    """Apply procedural Scots pine bark displacement to the active mesh"""
    bl_idname = "mesh.scots_pine_bark"
    bl_label = "Scots Pine Bark"
    bl_options = {'REGISTER', 'UNDO'}

    # Expose parameters as operator properties for the undo/redo panel
    seed: bpy.props.IntProperty(name="Seed", default=0, min=0, max=9999)
    plate_scale: bpy.props.FloatProperty(name="Plate Scale", default=0.8, min=0.1, max=5.0)
    y_stretch: bpy.props.FloatProperty(name="Y Stretch", default=8.0, min=2.0, max=20.0)
    num_levels: bpy.props.IntProperty(name="Num Levels", default=3, min=2, max=5)
    layer_depth: bpy.props.FloatProperty(name="Layer Depth", default=1.0, min=0.1, max=3.0)
    wall_steepness: bpy.props.FloatProperty(name="Wall Steepness", default=0.07, min=0.02, max=0.20)
    edge_fray: bpy.props.FloatProperty(name="Edge Fray", default=0.06, min=0.0, max=0.3)
    warp_x: bpy.props.FloatProperty(name="Warp X", default=1.2, min=0.0, max=4.0)
    warp_y: bpy.props.FloatProperty(name="Warp Y", default=0.15, min=0.0, max=2.0)
    sub_scale: bpy.props.FloatProperty(name="Sub Scale", default=2.5, min=1.0, max=5.0)
    sub_depth: bpy.props.FloatProperty(name="Sub Depth", default=0.15, min=0.0, max=0.5)
    hbreak_freq: bpy.props.FloatProperty(name="H-Break Freq", default=3.0, min=0.5, max=10.0)
    hbreak_depth: bpy.props.FloatProperty(name="H-Break Depth", default=0.25, min=0.0, max=1.0)
    crumple: bpy.props.FloatProperty(name="Crumple", default=0.12, min=0.0, max=0.5)
    per_plate_var: bpy.props.FloatProperty(name="Per-Plate Variation", default=0.8, min=0.0, max=1.0)
    plate_tilt: bpy.props.FloatProperty(name="Plate Tilt", default=0.08, min=0.0, max=0.3)
    fibre_grain: bpy.props.FloatProperty(name="Fibre Grain", default=0.03, min=0.0, max=0.15)
    micro_roughness: bpy.props.FloatProperty(name="Micro Roughness", default=0.02, min=0.0, max=0.1)
    overall_depth: bpy.props.FloatProperty(
        name="Overall Depth (mm)", default=3.0, min=0.5, max=8.0,
        description="Total displacement depth in mm (includes ~0.8mm coating compensation)"
    )

    @classmethod
    def poll(cls, context):
        obj = context.active_object
        return obj is not None and obj.type == 'MESH'

    def execute(self, context):
        obj = context.active_object

        # Build params dict from operator properties
        params = dict(DEFAULT_PARAMS)
        params["seed"] = self.seed
        params["plate_scale"] = self.plate_scale
        params["y_stretch"] = self.y_stretch
        params["num_levels"] = self.num_levels
        params["layer_depth"] = self.layer_depth
        params["wall_steepness"] = self.wall_steepness
        params["edge_fray"] = self.edge_fray
        params["warp_x"] = self.warp_x
        params["warp_y"] = self.warp_y
        params["sub_scale"] = self.sub_scale
        params["sub_depth"] = self.sub_depth
        params["hbreak_freq"] = self.hbreak_freq
        params["hbreak_depth"] = self.hbreak_depth
        params["crumple"] = self.crumple
        params["per_plate_var"] = self.per_plate_var
        params["plate_tilt"] = self.plate_tilt
        params["fibre_grain"] = self.fibre_grain
        params["micro_roughness"] = self.micro_roughness
        params["overall_depth"] = self.overall_depth

        # Work in object space via bmesh
        bm = bmesh.new()
        bm.from_mesh(obj.data)
        bm.verts.ensure_lookup_table()
        bm.faces.ensure_lookup_table()

        # Warn about mesh density
        if len(bm.faces) > 0:
            total_area = sum(f.calc_area() for f in bm.faces)
            avg_face_area = total_area / len(bm.faces)
            avg_face_size = math.sqrt(avg_face_area) * 1000.0  # to mm
            if avg_face_size > 3.0:
                self.report(
                    {'WARNING'},
                    f"Average face size is {avg_face_size:.1f} mm — "
                    f"consider subdividing for better detail (target < 2 mm)"
                )

        # Compute displacement per vertex
        # Blender default unit is metres; we work in mm for displacement
        # then convert back. Positions stay in metres for noise evaluation.
        mm_to_bu = 0.001  # 1 mm = 0.001 Blender units (metres)

        for v in bm.verts:
            n = v.normal
            if n.length < 1e-6:
                continue
            disp_mm = bark_displacement(v.co, n, params)
            v.co += n.normalized() * (disp_mm * mm_to_bu)

        bm.to_mesh(obj.data)
        bm.free()
        obj.data.update()

        self.report({'INFO'}, "Scots pine bark displacement applied")
        return {'FINISHED'}

    def invoke(self, context, event):
        return self.execute(context)


# ---------------------------------------------------------------------------
# Panel in the N-panel (Sidebar) for easy access
# ---------------------------------------------------------------------------

class VIEW3D_PT_scots_pine_bark(bpy.types.Panel):
    bl_label = "Scots Pine Bark"
    bl_idname = "VIEW3D_PT_scots_pine_bark"
    bl_space_type = 'VIEW_3D'
    bl_region_type = 'UI'
    bl_category = "Bark"

    def draw(self, context):
        layout = self.layout
        layout.operator("mesh.scots_pine_bark", text="Apply Bark Displacement")
        layout.separator()
        layout.label(text="Adjust in the Undo panel (F9)")
        layout.label(text="after applying.")
        layout.separator()
        layout.label(text="Tips:", icon='INFO')
        layout.label(text="  - Subdivide mesh to < 2mm faces")
        layout.label(text="  - Overall Depth includes coating")
        layout.label(text="    compensation (~0.8mm)")
        layout.label(text="  - Set units to Millimeters for")
        layout.label(text="    accurate print dimensions")


# ---------------------------------------------------------------------------
# Quick-run function (for running directly from Text Editor)
# ---------------------------------------------------------------------------

def quick_run():
    """Run bark displacement on the active object with default parameters.
    Call this when executing the script directly from Blender's Text Editor.
    """
    obj = bpy.context.active_object
    if obj is None or obj.type != 'MESH':
        print("ERROR: Select a mesh object first.")
        return

    print(f"Applying Scots pine bark to '{obj.name}' "
          f"({len(obj.data.vertices)} verts, {len(obj.data.polygons)} faces)...")

    # Ensure we're in object mode
    if bpy.context.mode != 'OBJECT':
        bpy.ops.object.mode_set(mode='OBJECT')

    params = dict(DEFAULT_PARAMS)

    # Work via bmesh
    bm = bmesh.new()
    bm.from_mesh(obj.data)
    bm.verts.ensure_lookup_table()
    bm.faces.ensure_lookup_table()

    # Mesh density check
    if len(bm.faces) > 0:
        total_area = sum(f.calc_area() for f in bm.faces)
        avg_face_size = math.sqrt(total_area / len(bm.faces)) * 1000.0
        print(f"  Average face size: {avg_face_size:.1f} mm")
        if avg_face_size > 3.0:
            print(f"  WARNING: Face size > 3mm. Consider adding a Subdivision Surface "
                  f"modifier (2–3 levels) for better detail.")

    mm_to_bu = 0.001
    total_verts = len(bm.verts)

    for i, v in enumerate(bm.verts):
        if (i + 1) % 1000 == 0 or i == total_verts - 1:
            print(f"  Processing vertex {i + 1}/{total_verts}...")
        n = v.normal
        if n.length < 1e-6:
            continue
        disp_mm = bark_displacement(v.co, n, params)
        v.co += n.normalized() * (disp_mm * mm_to_bu)

    bm.to_mesh(obj.data)
    bm.free()
    obj.data.update()
    print("Done! Bark displacement applied.")


# ---------------------------------------------------------------------------
# Registration
# ---------------------------------------------------------------------------

_classes = (
    MESH_OT_scots_pine_bark,
    VIEW3D_PT_scots_pine_bark,
)


def register():
    for cls in _classes:
        bpy.utils.register_class(cls)


def unregister():
    for cls in reversed(_classes):
        bpy.utils.unregister_class(cls)


# When run directly from Blender's Text Editor, register the addon
# and also run immediately on the active object.
if __name__ == "__main__":
    # Unregister first in case of re-run
    for cls in reversed(_classes):
        try:
            bpy.utils.unregister_class(cls)
        except RuntimeError:
            pass

    register()
    print("\n" + "=" * 60)
    print("Scots Pine Bark — registered.")
    print("  Operator: mesh.scots_pine_bark")
    print("  Panel: 3D Viewport > Sidebar (N) > Bark tab")
    print("=" * 60)

    # Auto-run on active object
    quick_run()
