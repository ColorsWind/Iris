package com.volmit.iris.core;

import com.volmit.iris.Iris;
import com.volmit.iris.core.tools.IrisToolbelt;
import com.volmit.iris.engine.framework.Engine;
import com.volmit.iris.engine.framework.IrisAccess;
import com.volmit.iris.engine.object.*;
import com.volmit.iris.engine.object.common.IObjectPlacer;
import com.volmit.iris.engine.object.tile.TileData;
import com.volmit.iris.util.collection.KList;
import com.volmit.iris.util.data.Cuboid;
import com.volmit.iris.util.math.BlockPosition;
import com.volmit.iris.util.math.RNG;
import com.volmit.iris.util.math.Vector2d;
import com.volmit.iris.util.scheduling.J;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.StructureGrowEvent;

import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

public class TreeManager implements Listener {

    public TreeManager() {
        Iris.instance.registerListener(this);
    }

    /**This function does the following
     * <br>1. Is the sapling growing in an Iris world? No -> exit</br>
     * <br>2. Is the Iris world accessible? No -> exit</br>
     * <br>3. Is the sapling overwriting setting on in that dimension? No -> exit</br>
     * <br>4. Check biome, region and dimension for overrides for that sapling type -> Found -> use</br>
     * <br>5. Exit if none are found, cancel event if one or more are.</br>
     * @param event Checks the given event for sapling overrides
     */
    @EventHandler
    public void onStructureGrowEvent(StructureGrowEvent event) {

        Iris.debug(this.getClass().getName() + " received a structure grow event");

        // Must be iris world
        if (!IrisToolbelt.isIrisWorld(event.getWorld())) {
            Iris.debug(this.getClass().getName() + " passed it off to vanilla since not an Iris world");
            return;
        }

        // Get world access
        IrisAccess worldAccess = IrisToolbelt.access(event.getWorld());
        if (worldAccess == null) {
            Iris.debug(this.getClass().getName() + " passed it off to vanilla because could not get IrisAccess for this world");
            Iris.reportError(new NullPointerException(event.getWorld().getName() + " could not be accessed despite being an Iris world"));
            return;
        }

        // Return null if not enabled
        if (!worldAccess.getCompound().getRootDimension().getTreeSettings().isEnabled()) {
            Iris.debug(this.getClass().getName() + " cancelled because tree overrides are disabled");
            return;
        }

        Iris.debug("Sapling grew @ " + event.getLocation() + " for " + event.getSpecies().name() + " usedBoneMeal is " + event.isFromBonemeal());

        // Calculate size, type & placement
        // TODO: Patch algorithm to retrieve saplings, as it's not working as intended (only ever returns 1x1)
        Cuboid saplingPlane = getSaplings(event.getLocation(), blockData -> event.getLocation().getBlock().getBlockData().equals(blockData), event.getWorld());
        Iris.debug("Sapling plane is: " + saplingPlane.getSizeX() + " by " + saplingPlane.getSizeZ());

        // Find best object placement based on sizes
        IrisObjectPlacement placement = getObjectPlacement(worldAccess, event.getLocation(), event.getSpecies(), new IrisTreeSize(1, 1));

        // If none were found, just exit
        if (placement == null) {
            Iris.debug(this.getClass().getName() + " had options but did not manage to find objectPlacements for them");
            return;
        }

        // Delete existing saplings
        saplingPlane.forEach(block -> block.setType(Material.AIR));

        // Get object from placer
        IrisObject object = worldAccess.getData().getObjectLoader().load(placement.getPlace().getRandom(RNG.r));

        // List of new blocks
        List<BlockState> blockStateList = new KList<>();

        // Create object placer
        IObjectPlacer placer = new IObjectPlacer() {

            @Override
            public int getHighest(int x, int z) {
                return event.getWorld().getHighestBlockYAt(x, z);
            }

            @Override
            public int getHighest(int x, int z, boolean ignoreFluid) {
                return event.getWorld().getHighestBlockYAt(x, z, ignoreFluid ? HeightMap.OCEAN_FLOOR : HeightMap.WORLD_SURFACE);
            }

            @Override
            public void set(int x, int y, int z, BlockData d) {
                Block b = event.getWorld().getBlockAt(x, y, z);
                BlockState state = b.getState();
                state.setBlockData(d);
                blockStateList.add(b.getState());
            }

            @Override
            public BlockData get(int x, int y, int z) {
                return event.getWorld().getBlockAt(x, y, z).getBlockData();
            }

            @Override
            public boolean isPreventingDecay() {
                return true;
            }

            @Override
            public boolean isSolid(int x, int y, int z) {
                return get(x,y,z).getMaterial().isSolid();
            }

            @Override
            public boolean isUnderwater(int x, int z) {
                return false;
            }

            @Override
            public int getFluidHeight() {
                Engine engine;
                if (worldAccess.getCompound().getSize() > 1) {
                    engine = worldAccess.getCompound().getEngine(0);
                } else {
                    engine = (Engine) worldAccess.getCompound().getRootDimension();
                }
                return engine.getDimension().getFluidHeight();
            }

            @Override
            public boolean isDebugSmartBore() {
                return false;
            }

            @Override
            public void setTile(int xx, int yy, int zz, TileData<? extends TileState> tile) {

            }
        };

        // TODO: Prevent placing on claimed blocks (however that's defined, idk)

        // TODO: Prevent placing object when overriding blocks

        // Place the object with the placer
        object.place(
                saplingPlane.getCenter(),
                placer,
                placement,
                RNG.r,
                Objects.requireNonNull(worldAccess).getData()
        );

        // Cancel the vanilla placement
        event.setCancelled(true);

        // Queue sync task
        J.s(() -> {

            // Send out a new event
            StructureGrowEvent iGrow = new StructureGrowEvent(event.getLocation(), event.getSpecies(), event.isFromBonemeal(), event.getPlayer(), blockStateList);
            Bukkit.getServer().getPluginManager().callEvent(iGrow);

            // Check if blocks need to be updated
            if(!iGrow.isCancelled()){
                for (BlockState block : iGrow.getBlocks()) {
                   block.update(true, false);
                }
            }
        });
    }

    /**
     * Finds a single object placement (which may contain more than one object) for the requirements species, location & size
     * @param worldAccess The world to access (check for biome, region, dimension, etc)
     * @param location The location of the growth event (For biome/region finding)
     * @param type The bukkit TreeType to match
     * @param size The size of the sapling area
     * @return An object placement which contains the matched tree, or null if none were found / it's disabled.
     */
    private IrisObjectPlacement getObjectPlacement(IrisAccess worldAccess, Location location, TreeType type, IrisTreeSize size) {

        KList<IrisObjectPlacement> placements = new KList<>();
        KList<IrisObjectPlacement> allObjects = new KList<>();
        boolean isUseAll = ((Engine)worldAccess.getEngineAccess(location.getBlockY())).getDimension().getTreeSettings().getMode().equals(IrisTreeModes.ALL);

        // Retrieve objectPlacements of type `species` from biome
        IrisBiome biome = worldAccess.getBiome(location.getBlockX(), location.getBlockY(), location.getBlockZ());
        placements.addAll(matchObjectPlacements(biome.getObjects(), size, type));
        allObjects.addAll(biome.getObjects());

        // Add more or find any in the region
        if (isUseAll || placements.isEmpty()){
            IrisRegion region = worldAccess.getCompound().getDefaultEngine().getRegion(location.getBlockX(), location.getBlockZ());
            placements.addAll(matchObjectPlacements(region.getObjects(), size, type));
            allObjects.addAll(region.getObjects());
        }

        // TODO: Add more or find any in the dimension
        //      Add object placer to dimension
        //        if (isUseAll || placements.isEmpty()){
        //            placements.addAll(matchObjectPlacements(worldAccess.getCompound().getRootDimension().getObjects(), size, type));
        //        }

        // Check if no matches were found, return a random one if they are
        return placements.isNotEmpty() ? placements.getRandom(RNG.r) : null;
    }

    /**
     * Filters out mismatches and returns matches
     * @param objects The object placements to check
     * @param size The size of the sapling area to filter with
     * @param type The type of the tree to filter with
     * @return A list of objectPlacements that matched. May be empty.
     */
    private KList<IrisObjectPlacement> matchObjectPlacements(KList<IrisObjectPlacement> objects, IrisTreeSize size, TreeType type) {

        Predicate<IrisTree> isValid = irisTree -> (
                irisTree.isAnySize() || irisTree.getSizes().stream().anyMatch(treeSize -> treeSize.doesMatch(size))) && (
                irisTree.isAnyTree() || irisTree.getTreeTypes().stream().anyMatch(treeType -> treeType.equals(type)));

        objects.removeIf(objectPlacement -> objectPlacement.getTrees().stream().noneMatch(isValid));

        return objects;
    }

    /**
     * Get the Cuboid of sapling sizes at a location & blockData predicate
     * @param at this location
     * @param valid with this blockData predicate
     * @param world the world to check in
     * @return A cuboid containing only saplings
     */
    public Cuboid getSaplings(Location at, Predicate<BlockData> valid, World world) {
        KList<BlockPosition> blockPositions = new KList<>();
        grow(at.getWorld(), new BlockPosition(at.getBlockX(), at.getBlockY(), at.getBlockZ()), valid, blockPositions);
        BlockPosition a = new BlockPosition(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE);
        BlockPosition b = new BlockPosition(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);

        // Maximise the block position in x and z to get max cuboid bounds
        for(BlockPosition blockPosition : blockPositions)
        {
            a.max(blockPosition);
            b.min(blockPosition);
        }

        // Create a cuboid with the size calculated before
        Cuboid cuboid = new Cuboid(a.toBlock(world).getLocation(), b.toBlock(world).getLocation());
        boolean cuboidIsValid = true;

        // Loop while the cuboid is larger than 2
        while(Math.min(cuboid.getSizeX(), cuboid.getSizeZ()) > 0)
        {
            checking:
            for(int i = cuboid.getLowerX(); i < cuboid.getUpperX(); i++)
            {
                for(int j = cuboid.getLowerY(); j < cuboid.getUpperY(); j++)
                {
                    for(int k = cuboid.getLowerZ(); k < cuboid.getUpperZ(); k++)
                    {
                        if(!blockPositions.contains(new BlockPosition(i,j,k)))
                        {
                            cuboidIsValid = false;
                            break checking;
                        }
                    }
                }
            }

            // Return this cuboid if it's valid
            if(cuboidIsValid)
            {
                return cuboid;
            }

            // Inset the cuboid and try again (revalidate)
            cuboid = cuboid.inset(Cuboid.CuboidDirection.Horizontal, 1);
            cuboidIsValid = true;
        }

        return new Cuboid(at, at);
    }

    /**
     * Grows the blockPosition list by means of checking neighbours in
     * @param world the world to check in
     * @param center the location of this position
     * @param valid validation on blockData to check block with
     * @param l list of block positions to add new neighbors too
     */
    private void grow(World world, BlockPosition center, Predicate<BlockData> valid, KList<BlockPosition> l) {
        // Make sure size is less than 50, the block to check isn't already in, and make sure the blockData still matches
        if(l.size() <= 50 && !l.contains(center) && valid.test(center.toBlock(world).getBlockData()))
        {
            l.add(center);
            grow(world, center.add(1, 0, 0), valid, l);
            grow(world, center.add(-1, 0, 0), valid, l);
            grow(world, center.add(0, 0, 1), valid, l);
            grow(world, center.add(0, 0, -1), valid, l);
        }
    }
}
