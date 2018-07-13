package de.ellpeck.rockbottom.world;

import com.google.common.base.Preconditions;
import de.ellpeck.rockbottom.api.Constants;
import de.ellpeck.rockbottom.api.GameContent;
import de.ellpeck.rockbottom.api.IGameInstance;
import de.ellpeck.rockbottom.api.RockBottomAPI;
import de.ellpeck.rockbottom.api.data.set.DataSet;
import de.ellpeck.rockbottom.api.data.set.ModBasedDataSet;
import de.ellpeck.rockbottom.api.entity.Entity;
import de.ellpeck.rockbottom.api.entity.player.AbstractEntityPlayer;
import de.ellpeck.rockbottom.api.entity.spawn.DespawnHandler;
import de.ellpeck.rockbottom.api.event.EventResult;
import de.ellpeck.rockbottom.api.event.impl.*;
import de.ellpeck.rockbottom.api.tile.Tile;
import de.ellpeck.rockbottom.api.tile.entity.TileEntity;
import de.ellpeck.rockbottom.api.tile.state.TileState;
import de.ellpeck.rockbottom.api.util.BoundBox;
import de.ellpeck.rockbottom.api.util.Counter;
import de.ellpeck.rockbottom.api.util.Pos3;
import de.ellpeck.rockbottom.api.util.Util;
import de.ellpeck.rockbottom.api.util.reg.ResourceName;
import de.ellpeck.rockbottom.api.world.IChunk;
import de.ellpeck.rockbottom.api.world.IWorld;
import de.ellpeck.rockbottom.api.world.gen.INoiseGen;
import de.ellpeck.rockbottom.api.world.gen.IWorldGenerator;
import de.ellpeck.rockbottom.api.world.gen.biome.Biome;
import de.ellpeck.rockbottom.api.world.gen.biome.level.BiomeLevel;
import de.ellpeck.rockbottom.api.world.layer.TileLayer;
import de.ellpeck.rockbottom.net.packet.toclient.PacketEntityChange;
import de.ellpeck.rockbottom.net.packet.toclient.PacketScheduledUpdate;
import de.ellpeck.rockbottom.net.packet.toclient.PacketTileChange;
import de.ellpeck.rockbottom.world.entity.player.EntityPlayer;

import java.util.*;
import java.util.function.Predicate;
import java.util.logging.Level;

public class Chunk implements IChunk {

    public static boolean isGeneratingChunk;

    public final int x;
    public final int y;

    public final int gridX;
    public final int gridY;
    public final List<AbstractEntityPlayer> playersInRange = new ArrayList<>();
    public final List<AbstractEntityPlayer> playersOutOfRangeCached = new ArrayList<>();
    public final Map<AbstractEntityPlayer, Counter> playersOutOfRangeCachedTimers = new HashMap<>();
    protected final World world;
    protected final Biome[][] biomeGrid = new Biome[Constants.CHUNK_SIZE][Constants.CHUNK_SIZE];
    protected final Map<TileLayer, TileState[][]> stateGrid = new HashMap<>();
    protected final List<TileLayer> layersByRenderPrio = new ArrayList<>();
    protected final byte[][][] lightGrid = new byte[2][Constants.CHUNK_SIZE][Constants.CHUNK_SIZE];
    protected final List<Entity> entities = new ArrayList<>();
    protected final Map<UUID, Entity> entityLookup = new HashMap<>();
    protected final List<TileEntity> tileEntities = new ArrayList<>();
    protected final Map<Pos3, TileEntity> tileEntityLookup = new HashMap<>();
    protected final List<TileEntity> tickingTileEntities = new ArrayList<>();
    protected final List<ScheduledUpdate> scheduledUpdates = new ArrayList<>();
    protected final Map<Pos3, ScheduledUpdate> scheduledUpdateLookup = new HashMap<>();
    protected final List<Entity> entitiesUnmodifiable;
    protected final List<TileEntity> tileEntitiesUnmodifiable;
    protected final List<TileEntity> tickingTileEntitiesUnmodifiable;
    private final Map<Biome, Counter> biomeAmounts = new HashMap<>();
    private final Map<TileLayer, int[]> heights = new HashMap<>();
    private final Map<TileLayer, Integer> averageHeights = new HashMap<>();
    private final boolean constantlyPersistent;
    private final Map<TileLayer, Float> flatness = new HashMap<>();
    public boolean isGenerating = true;
    protected boolean needsSave;
    private int internalLoadingTimer;
    private ModBasedDataSet additionalData;
    private Biome mostProminentBiome = GameContent.BIOME_SKY;
    private float fadePercentage;

    public Chunk(World world, int gridX, int gridY, boolean constantlyPersistent) {
        this.world = world;
        this.constantlyPersistent = constantlyPersistent;

        this.x = Util.toWorldPos(gridX);
        this.y = Util.toWorldPos(gridY);
        this.gridX = gridX;
        this.gridY = gridY;

        this.internalLoadingTimer = Constants.CHUNK_LOAD_TIME;

        for (int x = 0; x < Constants.CHUNK_SIZE; x++) {
            for (int y = 0; y < Constants.CHUNK_SIZE; y++) {
                this.biomeGrid[x][y] = GameContent.BIOME_SKY;
            }
        }

        this.entitiesUnmodifiable = Collections.unmodifiableList(this.entities);
        this.tileEntitiesUnmodifiable = Collections.unmodifiableList(this.tileEntities);
        this.tickingTileEntitiesUnmodifiable = Collections.unmodifiableList(this.tickingTileEntities);
    }

    private void generate(List<? extends IWorldGenerator> gens) {
        if (isGeneratingChunk) {
            RockBottomAPI.logger().log(Level.WARNING, "CHUNK GEN BLEEDING INTO DIFFERENT CHUNK AT " + this.gridX + ", " + this.gridY + "! THIS SHOULD NOT HAPPEN!", new IllegalStateException());
        }

        isGeneratingChunk = true;

        for (IWorldGenerator generator : gens) {
            if (this.canGenerate(generator)) {
                EventResult result = RockBottomAPI.getEventHandler().fireEvent(new WorldGenEvent(this.world, this, generator));
                if (result != EventResult.CANCELLED && (result == EventResult.MODIFIED || generator.shouldGenerate(this.world, this))) {
                    generator.generate(this.world, this);
                }
            }
        }

        isGeneratingChunk = false;
    }

    private boolean canGenerate(IWorldGenerator generator) {
        if (generator.needsPlayerToAllowGeneration(this.world, this)) {
            for (AbstractEntityPlayer player : this.world.players) {
                if (generator.doesPlayerAllowGeneration(this.world, this, player)) {
                    return true;
                }
            }
            return false;
        } else {
            return true;
        }
    }

    protected void updateEntities(IGameInstance game) {
        if (this.fadePercentage < 1F) {
            this.fadePercentage += 0.02F;
        }

        for (int i = this.entities.size() - 1; i >= 0; i--) {
            Entity entity = this.entities.get(i);

            if (RockBottomAPI.getEventHandler().fireEvent(new EntityTickEvent(entity)) != EventResult.CANCELLED) {
                entity.update(game);
            }

            if (entity.shouldBeRemoved()) {
                this.world.removeEntity(entity, this);
            } else {
                double x = entity.getX();
                double y = entity.getY();

                if (!this.tryDespawn(entity, x, y)) {
                    int newChunkX = Util.toGridPos(x);
                    int newChunkY = Util.toGridPos(y);

                    if (newChunkX != this.gridX || newChunkY != this.gridY) {
                        this.removeEntity(entity);

                        IChunk chunk = this.world.getChunkFromGridCoords(newChunkX, newChunkY);
                        chunk.addEntity(entity);

                        if (this.world.isServer()) {
                            for (AbstractEntityPlayer player : chunk.getPlayersInRange()) {
                                if (!this.playersInRange.contains(player)) {
                                    player.sendPacket(new PacketEntityChange(entity, false));

                                    RockBottomAPI.logger().config("Adding entity " + entity + " with id " + entity.getUniqueId() + " to chunk in range of player with id " + player.getUniqueId());
                                }
                            }
                        }
                    }
                }
            }
        }

        for (int i = this.tickingTileEntities.size() - 1; i >= 0; i--) {
            TileEntity tile = this.tickingTileEntities.get(i);

            Preconditions.checkState(this.getTileEntity(tile.layer, tile.x, tile.y) == tile, "There is a ticking tile entity at " + tile.x + ", " + tile.y + " that shouldn't exist there as there is no tile entity registered for that position!");

            if (RockBottomAPI.getEventHandler().fireEvent(new TileEntityTickEvent(tile)) != EventResult.CANCELLED) {
                tile.update(game);
            }

            if (tile.shouldRemove()) {
                this.removeTileEntity(tile.layer, tile.x, tile.y);
            }
        }
    }

    private boolean tryDespawn(Entity entity, double x, double y) {
        DespawnHandler handler = entity.getDespawnHandler();
        if (handler != null) {
            if (this.world.getTotalTime() % handler.getDespawnFrequency(this.world) == 0 && handler.isReadyToDespawn(entity)) {
                AbstractEntityPlayer player = this.world.getClosestPlayer(x, y);
                double dist = handler.getMaxPlayerDistance(entity);
                if (Util.distanceSq(player.getX(), player.getY(), x, y) >= dist * dist) {
                    handler.despawn(entity);
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public void update(IGameInstance game) {
        if (!this.isGenerating) {
            this.updateEntities(game);

            int layers = TileLayer.getAllLayers().size();
            for (int i = 0; i < Constants.RANDOM_TILE_UPDATES * layers; i++) {
                TileLayer layer = TileLayer.getAllLayers().get(Util.RANDOM.nextInt(layers));
                int randX = Util.RANDOM.nextInt(Constants.CHUNK_SIZE);
                int randY = Util.RANDOM.nextInt(Constants.CHUNK_SIZE);

                Tile tile = this.getStateInner(layer, randX, randY).getTile();
                tile.updateRandomly(this.world, this.x + randX, this.y + randY, layer);
            }

            if (!this.scheduledUpdates.isEmpty()) {
                for (int i = this.scheduledUpdates.size() - 1; i >= 0; i--) {
                    ScheduledUpdate update = this.scheduledUpdates.get(i);
                    update.time--;

                    if (update.time <= 0) {
                        this.scheduledUpdates.remove(i);
                        this.scheduledUpdateLookup.remove(new Pos3(update.x, update.y, update.layer.index()));

                        Tile tile = this.getState(update.layer, update.x, update.y).getTile();
                        if (tile == update.tile.getTile()) {
                            tile.onScheduledUpdate(this.world, update.x, update.y, update.layer, update.scheduledMeta);

                            if (this.world.isServer()) {
                                RockBottomAPI.getNet().sendToAllPlayersWithLoadedPos(this.world, new PacketScheduledUpdate(update.layer, update.x, update.y, update.scheduledMeta), update.x, update.y);
                            }
                        }

                        this.setDirty();
                    }
                }
            }
        }

        if (this.internalLoadingTimer > 0) {
            this.internalLoadingTimer--;
        }

        for (int i = this.playersOutOfRangeCached.size() - 1; i >= 0; i--) {
            AbstractEntityPlayer player = this.playersOutOfRangeCached.get(i);

            Counter time = this.playersOutOfRangeCachedTimers.get(player);
            time.add(-1);

            if (time.get() <= 0) {
                player.getChunksInRange().remove(this);
                player.onChunkUnloaded(this);

                this.playersOutOfRangeCached.remove(i);
                this.playersOutOfRangeCachedTimers.remove(player);
            }
        }
    }

    @Override
    public TileState getState(int x, int y) {
        return this.getState(TileLayer.MAIN, x, y);
    }

    @Override
    public TileState getState(TileLayer layer, int x, int y) {
        return this.getStateInner(layer, x - this.x, y - this.y);
    }

    @Override
    public void setState(int x, int y, TileState tile) {
        this.setState(TileLayer.MAIN, x, y, tile);
    }

    @Override
    public void setState(TileLayer layer, int x, int y, TileState tile) {
        this.setStateInner(layer, x - this.x, y - this.y, tile);
    }

    @Override
    public TileState getStateInner(TileLayer layer, int x, int y) {
        TileState[][] grid = this.getGrid(layer, false);

        if (grid != null) {
            TileState state = grid[x][y];
            if (state == null) {
                RockBottomAPI.logger().log(Level.WARNING, "In chunk at " + this.gridX + ' ' + this.gridY + ", position " + x + ' ' + y + " is null!?", new NullPointerException());
            }
            return state;
        } else {
            return GameContent.TILE_AIR.getDefState();
        }
    }

    @Override
    public TileState getStateInner(int x, int y) {
        return this.getStateInner(TileLayer.MAIN, x, y);
    }

    @Override
    public void setStateInner(int x, int y, TileState tile) {
        this.setStateInner(TileLayer.MAIN, x, y, tile);
    }

    @Override
    public void setStateInner(TileLayer layer, int x, int y, TileState state) {
        SetStateEvent event = new SetStateEvent(this, state, layer, x, y);
        if (RockBottomAPI.getEventHandler().fireEvent(event) != EventResult.CANCELLED) {
            state = event.state;
            layer = event.layer;
            x = event.x;
            y = event.y;

            Preconditions.checkNotNull(state, "Tried setting null tile in chunk at " + this.gridX + ", " + this.gridY + '!');
            Preconditions.checkNotNull(layer, "Tried setting tile to null layer in chunk at " + this.gridX + ", " + this.gridY + '!');

            TileState lastState = this.getStateInner(layer, x, y);
            if (state != lastState) {
                Tile tile = state.getTile();
                Preconditions.checkArgument(layer.canTileBeInLayer(this.world, this.x + x, this.y + y, tile), "Tried setting tile " + state + " at " + (this.x + x) + ", " + (this.y + y) + " on layer " + layer + " that doesn't allow it!");

                Tile lastTile = lastState.getTile();
                boolean oldHeightMap = lastTile.factorsIntoHeightMap(this.world, this.x + x, this.y + y, layer);

                if (tile != lastTile) {
                    lastTile.onRemoved(this.world, this.x + x, this.y + y, layer);

                    if (layer.canHoldTileEntities() && lastTile.canProvideTileEntity()) {
                        this.removeTileEntity(layer, this.x + x, this.y + y);
                    }
                }

                TileState[][] grid = this.getGrid(layer, !tile.isAir());
                if (grid != null) {
                    grid[x][y] = state;
                }

                if (tile != lastTile) {
                    if (layer.canHoldTileEntities() && tile.canProvideTileEntity()) {
                        TileEntity tileEntity = tile.provideTileEntity(this.world, this.x + x, this.y + y, layer);
                        if (tileEntity != null) {
                            this.addTileEntity(tileEntity);
                        }
                    }

                    tile.onAdded(this.world, this.x + x, this.y + y, layer);
                }

                boolean newHeightMap = tile.factorsIntoHeightMap(this.world, this.x + x, this.y + y, layer);
                if (newHeightMap != oldHeightMap) {
                    int newHeight = 0;

                    if (!newHeightMap) {
                        for (int checkY = y - 1; checkY >= 0; checkY--) {
                            if (this.getStateInner(layer, x, checkY).getTile().factorsIntoHeightMap(this.world, this.x + x, this.y + checkY, layer)) {
                                newHeight = checkY + 1;
                                break;
                            }
                        }
                    } else {
                        newHeight = y + 1;
                    }

                    int[] heights = this.heights.computeIfAbsent(layer, l -> new int[Constants.CHUNK_SIZE]);
                    if (heights[x] < newHeight || heights[x] == y + 1) {
                        heights[x] = newHeight;

                        Set<Integer> uniqueHeights = new HashSet<>();
                        int totalHeight = 0;

                        for (int checkX = 0; checkX < Constants.CHUNK_SIZE; checkX++) {
                            uniqueHeights.add(heights[checkX]);
                            totalHeight += heights[checkX];
                        }

                        this.flatness.put(layer, 1F - (uniqueHeights.size() - 1F) / (Constants.CHUNK_SIZE - 1F));
                        this.averageHeights.put(layer, totalHeight / Constants.CHUNK_SIZE);
                    }
                }

                if (!this.isGenerating) {
                    this.world.causeLightUpdate(this.x + x, this.y + y);
                    this.world.notifyNeighborsOfChange(this.x + x, this.y + y, layer);

                    if (this.world.isServer() && this.getStateInner(layer, x, y) == state) {
                        RockBottomAPI.getNet().sendToAllPlayersWithLoadedPos(this.world, new PacketTileChange(this.x + x, this.y + y, layer, this.world.getIdForState(state)), this.x + x, this.y + y);
                    }

                    this.setDirty();
                }
            }
        }
    }

    @Override
    public void addEntity(Entity entity) {
        if (this.entityLookup.containsKey(entity.getUniqueId())) {
            RockBottomAPI.logger().warning("Tried adding entity " + entity + " with id " + entity.getUniqueId() + " to chunk at " + this.gridX + ", " + this.gridY + " that already contained it!");
        } else {
            this.entities.add(entity);
            this.entityLookup.put(entity.getUniqueId(), entity);

            entity.moveToChunk(this);

            if (!this.isGenerating) {
                this.setDirty();
            }
        }
    }

    @Override
    public void addTileEntity(TileEntity tile) {
        Preconditions.checkArgument(tile.layer.canHoldTileEntities(), "Tried adding tile entity " + tile + " at " + tile.x + ", " + tile.y + " on layer " + tile.layer + " that doesn't allow tile entities!");

        Pos3 posVec = new Pos3(tile.x, tile.y, tile.layer.index());
        if (!this.tileEntityLookup.containsKey(posVec)) {
            this.tileEntities.add(tile);
            this.tileEntityLookup.put(posVec, tile);

            if (tile.doesTick()) {
                this.tickingTileEntities.add(tile);
            }

            if (!this.isGenerating) {
                this.world.notifyNeighborsOfChange(tile.x, tile.y, tile.layer);
                this.setDirty();
            }
        }
    }

    @Override
    public void removeEntity(Entity entity) {
        this.entities.remove(entity);
        this.entityLookup.remove(entity.getUniqueId());

        if (!this.isGenerating) {
            this.setDirty();
        }
    }

    @Override
    public void removeTileEntity(TileLayer layer, int x, int y) {
        TileEntity tile = this.getTileEntity(layer, x, y);
        if (tile != null) {
            this.tileEntities.remove(tile);
            this.tileEntityLookup.remove(new Pos3(tile.x, tile.y, tile.layer.index()));

            if (tile.doesTick()) {
                this.tickingTileEntities.remove(tile);
            }

            if (!this.isGenerating) {
                this.world.notifyNeighborsOfChange(this.x + x, this.y + y, tile.layer);
                this.setDirty();
            }
        }
    }

    @Override
    public TileEntity getTileEntity(TileLayer layer, int x, int y) {
        return this.tileEntityLookup.get(new Pos3(x, y, layer.index()));
    }

    @Override
    public TileEntity getTileEntity(int x, int y) {
        return this.getTileEntity(TileLayer.MAIN, x, y);
    }

    @Override
    public <T extends TileEntity> T getTileEntity(TileLayer layer, int x, int y, Class<T> tileClass) {
        TileEntity tile = this.getTileEntity(layer, x, y);
        if (tile != null && tileClass.isAssignableFrom(tile.getClass())) {
            return (T) tile;
        } else {
            return null;
        }
    }

    @Override
    public <T extends TileEntity> T getTileEntity(int x, int y, Class<T> tileClass) {
        return this.getTileEntity(TileLayer.MAIN, x, y, tileClass);
    }

    @Override
    public void reevaluateTickBehavior(TileEntity tile) {
        if (tile.doesTick()) {
            if (!this.tickingTileEntities.contains(tile)) {
                this.tickingTileEntities.add(tile);
            }
        } else {
            this.tickingTileEntities.remove(tile);
        }
    }

    @Override
    public List<Entity> getAllEntities() {
        return this.entitiesUnmodifiable;
    }

    @Override
    public List<TileEntity> getAllTileEntities() {
        return this.tileEntitiesUnmodifiable;
    }

    @Override
    public List<TileEntity> getAllTickingTileEntities() {
        return this.tickingTileEntitiesUnmodifiable;
    }

    @Override
    public Entity getEntity(UUID id) {
        return this.entityLookup.get(id);
    }

    @Override
    public List<Entity> getEntities(BoundBox area) {
        return this.getEntities(area, null, null);
    }

    @Override
    public List<Entity> getEntities(BoundBox area, Predicate<Entity> test) {
        return this.getEntities(area, null, test);
    }

    @Override
    public <T extends Entity> List<T> getEntities(BoundBox area, Class<T> type) {
        return this.getEntities(area, type, null);
    }

    @Override
    public <T extends Entity> List<T> getEntities(BoundBox area, Class<T> type, Predicate<T> test) {
        List<T> entities = new ArrayList<>();

        for (Entity entity : this.entities) {
            if (!entity.isDead() && (type == null || type.isAssignableFrom(entity.getClass()))) {
                T castEntity = (T) entity;
                if (test == null || test.test(castEntity)) {
                    if (entity.currentBounds.intersects(area)) {
                        entities.add(castEntity);
                    }
                }
            }
        }

        return entities;
    }

    @Override
    public byte getCombinedLight(int x, int y) {
        return this.getCombinedLightInner(x - this.x, y - this.y);
    }

    @Override
    public byte getSkyLight(int x, int y) {
        return this.getSkylightInner(x - this.x, y - this.y);
    }

    @Override
    public byte getArtificialLight(int x, int y) {
        return this.getArtificialLightInner(x - this.x, y - this.y);
    }

    @Override
    public void setSkyLight(int x, int y, byte light) {
        this.setSkylightInner(x - this.x, y - this.y, light);
    }

    @Override
    public void setArtificialLight(int x, int y, byte light) {
        this.setArtificialLightInner(x - this.x, y - this.y, light);
    }

    @Override
    public void scheduleUpdate(int x, int y, TileLayer layer, int scheduledMeta, int time) {
        Pos3 posVec = new Pos3(x, y, layer.index());
        if (!this.scheduledUpdateLookup.containsKey(posVec)) {
            ScheduledUpdate update = new ScheduledUpdate(x, y, layer, this.getState(layer, x, y), scheduledMeta, time);

            this.scheduledUpdateLookup.put(posVec, update);
            this.scheduledUpdates.add(update);

            if (!this.isGenerating) {
                this.setDirty();
            }
        }
    }

    @Override
    public void scheduleUpdate(int x, int y, TileLayer layer, int time) {
        this.scheduleUpdate(x, y, layer, 0, time);
    }

    @Override
    public void setDirty(int x, int y) {
        this.setDirty();
    }

    @Override
    public int getChunkHeight(TileLayer layer, int x, int bottomY) {
        return this.getHeight(layer, x);
    }

    @Override
    public int getAverageChunkHeight(TileLayer layer, int x, int bottomY) {
        return this.getAverageHeight(layer);
    }

    @Override
    public float getChunkFlatness(TileLayer layer, int x, int y) {
        return this.getFlatness(layer);
    }

    @Override
    public int getHeight(TileLayer layer, int x) {
        return this.getHeightInner(layer, x - this.x);
    }

    @Override
    public Biome getExpectedBiome(int x, int y) {
        return this.world.getExpectedBiome(x, y);
    }

    @Override
    public BiomeLevel getExpectedBiomeLevel(int x, int y) {
        return this.world.getExpectedBiomeLevel(x, y);
    }

    @Override
    public int getExpectedSurfaceHeight(TileLayer layer, int x) {
        return this.world.getExpectedSurfaceHeight(layer, x);
    }

    @Override
    public int getExpectedAverageHeight(TileLayer layer, int startX, int endX) {
        return this.world.getExpectedAverageHeight(layer, startX, endX);
    }

    @Override
    public float getExpectedSurfaceFlatness(TileLayer layer, int startX, int endX) {
        return this.world.getExpectedSurfaceFlatness(layer, startX, endX);
    }

    @Override
    public INoiseGen getNoiseGenForBiome(Biome biome) {
        return this.world.getNoiseGenForBiome(biome);
    }

    @Override
    public boolean isConstantlyPersistent() {
        return this.constantlyPersistent;
    }

    @Override
    public boolean isGenerating() {
        return this.isGenerating;
    }

    @Override
    public void setGenerating(boolean generating) {
        this.isGenerating = generating;
    }

    @Override
    public float getFadePercentage() {
        return this.fadePercentage;
    }

    @Override
    public Biome getBiome(int x, int y) {
        return this.getBiomeInner(x - this.x, y - this.y);
    }

    @Override
    public void setBiome(int x, int y, Biome biome) {
        this.setBiomeInner(x - this.x, y - this.y, biome);
    }

    @Override
    public boolean isClient() {
        return this.world.isClient();
    }

    @Override
    public boolean isServer() {
        return this.world.isServer();
    }

    @Override
    public boolean isDedicatedServer() {
        return this.world.isDedicatedServer();
    }

    @Override
    public boolean isLocalPlayer(Entity entity) {
        return this.world.isLocalPlayer(entity);
    }

    @Override
    public void callRetroactiveGeneration() {
        this.generate(this.world.getSortedRetroactiveGenerators());
    }

    @Override
    public long getSeed() {
        return this.world.getSeed();
    }

    @Override
    public byte getCombinedLightInner(int x, int y) {
        byte artificial = this.getArtificialLightInner(x, y);
        byte sky = (byte) (this.getSkylightInner(x, y) * this.world.getSkylightModifier(true));

        return (byte) Math.min(Constants.MAX_LIGHT, artificial + sky);
    }

    @Override
    public byte getSkylightInner(int x, int y) {
        return this.lightGrid[0][x][y];
    }

    @Override
    public void setSkylightInner(int x, int y, byte light) {
        this.lightGrid[0][x][y] = light;

        if (!this.isGenerating) {
            this.setDirty();
        }
    }

    @Override
    public byte getArtificialLightInner(int x, int y) {
        return this.lightGrid[1][x][y];
    }

    @Override
    public void setArtificialLightInner(int x, int y, byte light) {
        this.lightGrid[1][x][y] = light;

        if (!this.isGenerating) {
            this.setDirty();
        }
    }

    @Override
    public boolean needsSave() {
        return this.needsSave;
    }

    @Override
    public boolean shouldUnload() {
        if (!this.constantlyPersistent && this.internalLoadingTimer <= 0 && this.playersInRange.isEmpty() && this.playersOutOfRangeCached.isEmpty()) {
            if (this.doesEntityForcePersistence()) {
                this.internalLoadingTimer = Constants.CHUNK_LOAD_TIME;

                RockBottomAPI.logger().fine("Persisting chunk at " + this.gridX + ", " + this.gridY);
            } else {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean doesEntityForcePersistence() {
        for (TileEntity tile : this.tileEntities) {
            if (tile.shouldMakeChunkPersist(this)) {
                return true;
            }
        }
        for (Entity entity : this.entities) {
            if (entity.shouldMakeChunkPersist(this)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void setDirty() {
        this.needsSave = true;
    }

    @Override
    public void save(DataSet set) {
        RockBottomAPI.getEventHandler().fireEvent(new ChunkSaveEvent(this, RockBottomAPI.getGame().getDataManager()));

        int layerCounter = 0;
        for (TileLayer layer : this.stateGrid.keySet()) {
            int[] ids = new int[Constants.CHUNK_SIZE * Constants.CHUNK_SIZE];

            int counter = 0;
            for (int x = 0; x < Constants.CHUNK_SIZE; x++) {
                for (int y = 0; y < Constants.CHUNK_SIZE; y++) {
                    ids[counter] = this.world.getIdForState(this.getStateInner(layer, x, y));
                    counter++;
                }
            }

            set.addIntArray("l_" + layerCounter, ids);
            set.addString("ln_" + layerCounter, layer.getName().toString());

            layerCounter++;
        }
        set.addInt("l_a", layerCounter);

        short[] biomes = new short[Constants.CHUNK_SIZE * Constants.CHUNK_SIZE];
        int biomeCounter = 0;
        for (int x = 0; x < Constants.CHUNK_SIZE; x++) {
            for (int y = 0; y < Constants.CHUNK_SIZE; y++) {
                biomes[biomeCounter] = (short) this.world.getIdForBiome(this.getBiomeInner(x, y));
                biomeCounter++;
            }
        }
        set.addShortArray("bi", biomes);


        for (int i = 0; i < this.lightGrid.length; i++) {
            byte[] light = new byte[Constants.CHUNK_SIZE * Constants.CHUNK_SIZE];
            int counter = 0;
            for (int x = 0; x < Constants.CHUNK_SIZE; x++) {
                for (int y = 0; y < Constants.CHUNK_SIZE; y++) {
                    light[counter] = this.lightGrid[i][x][y];
                    counter++;
                }
            }

            set.addByteArray("lg_" + i, light);
        }

        int entityId = 0;
        for (Entity entity : this.entities) {
            if (entity.doesSave() && !(entity instanceof EntityPlayer)) {
                DataSet entitySet = new DataSet();
                entitySet.addUniqueId("uuid", entity.getUniqueId());
                entitySet.addString("name", RockBottomAPI.ENTITY_REGISTRY.getId(entity.getClass()).toString());
                entity.save(entitySet);

                set.addDataSet("e_" + entityId, entitySet);

                entityId++;
            }
        }
        set.addInt("e_a", entityId);

        int tileEntityId = 0;
        for (TileEntity tile : this.tileEntities) {
            if (tile.doesSave()) {
                DataSet tileSet = new DataSet();
                tileSet.addInt("x", tile.x);
                tileSet.addInt("y", tile.y);
                tileSet.addString("layer", tile.layer.getName().toString());
                tile.save(tileSet, false);

                set.addDataSet("t_" + tileEntityId, tileSet);

                tileEntityId++;
            }
        }
        set.addInt("t_a", tileEntityId);

        DataSet updateSet = new DataSet();

        int updateId = 0;
        for (ScheduledUpdate update : this.scheduledUpdates) {
            updateSet.addInt("x_" + updateId, update.x);
            updateSet.addInt("y_" + updateId, update.y);
            updateSet.addString("l_" + updateId, update.layer.getName().toString());
            updateSet.addInt("m_" + updateId, update.scheduledMeta);
            updateSet.addInt("t_" + updateId, update.time);
            updateSet.addInt("i_" + updateId, this.world.getIdForState(update.tile));

            updateId++;
        }
        updateSet.addInt("a", updateId);

        set.addDataSet("s_u", updateSet);

        if (this.additionalData != null) {
            set.addModBasedDataSet("ad_da", this.additionalData);
        }

        this.needsSave = false;
    }

    public void loadOrCreate(DataSet set) {
        this.isGenerating = true;

        if (set != null && !set.isEmpty()) {
            int layerAmount = set.getInt("l_a");

            for (int i = 0; i < layerAmount; i++) {
                ResourceName res = new ResourceName(set.getString("ln_" + i));
                TileLayer layer = RockBottomAPI.TILE_LAYER_REGISTRY.get(res);
                if (layer != null) {
                    int[] ids = set.getIntArray("l_" + i, Constants.CHUNK_SIZE * Constants.CHUNK_SIZE);

                    int counter = 0;
                    for (int x = 0; x < Constants.CHUNK_SIZE; x++) {
                        for (int y = 0; y < Constants.CHUNK_SIZE; y++) {
                            TileState tile = this.world.getStateForId(ids[counter]);
                            if (tile != null) {
                                this.setStateInner(layer, x, y, tile);
                            } else {
                                RockBottomAPI.logger().warning("Could not load tile at " + x + ' ' + y + " because id " + ids[counter] + " is missing!");
                            }
                            counter++;
                        }
                    }
                } else {
                    RockBottomAPI.logger().warning("Could not load tile layer with name " + res + " as it is missing!");
                }
            }

            short[] biomes = set.getShortArray("bi", Constants.CHUNK_SIZE * Constants.CHUNK_SIZE);
            int biomeCounter = 0;
            for (int x = 0; x < Constants.CHUNK_SIZE; x++) {
                for (int y = 0; y < Constants.CHUNK_SIZE; y++) {
                    Biome biome = this.world.getBiomeForId(biomes[biomeCounter]);
                    if (biome != null) {
                        this.setBiomeInner(x, y, biome);
                    } else {
                        RockBottomAPI.logger().warning("Could not load biome at " + x + ' ' + y + " because id " + biomes[biomeCounter] + " is missing!");
                    }
                    biomeCounter++;
                }
            }

            for (int i = 0; i < this.lightGrid.length; i++) {
                byte[] light = set.getByteArray("lg_" + i, Constants.CHUNK_SIZE * Constants.CHUNK_SIZE);
                int counter = 0;
                for (int x = 0; x < Constants.CHUNK_SIZE; x++) {
                    for (int y = 0; y < Constants.CHUNK_SIZE; y++) {
                        this.lightGrid[i][x][y] = light[counter];
                        counter++;
                    }
                }
            }

            int entityAmount = set.getInt("e_a");
            for (int i = 0; i < entityAmount; i++) {
                DataSet entitySet = set.getDataSet("e_" + i);

                UUID id = entitySet.getUniqueId("uuid");
                String name = entitySet.getString("name");
                Entity entity = Util.createEntity(new ResourceName(name), this.world);

                if (entity != null) {
                    entity.load(entitySet);
                    entity.setUniqueId(id);
                    this.addEntity(entity);
                } else {
                    RockBottomAPI.logger().warning("Couldn't load entity with name " + name + " and data " + entitySet);
                }
            }

            int tileEntityAmount = set.getInt("t_a");
            for (int i = 0; i < tileEntityAmount; i++) {
                DataSet tileSet = set.getDataSet("t_" + i);
                int x = tileSet.getInt("x");
                int y = tileSet.getInt("y");

                ResourceName res = new ResourceName(tileSet.getString("layer"));
                TileLayer layer = RockBottomAPI.TILE_LAYER_REGISTRY.get(res);
                if (layer != null) {
                    TileEntity tile = this.getTileEntity(layer, x, y);
                    if (tile != null) {
                        tile.load(tileSet, false);
                    } else {
                        RockBottomAPI.logger().warning("Couldn't load data of tile entity at " + x + ", " + y + " because it is missing!");
                    }
                } else {
                    RockBottomAPI.logger().warning("Could not tile entity at " + x + ' ' + y + " because layer with name " + res + " is missing!");
                }
            }

            DataSet updateSet = set.getDataSet("s_u");

            int updateAmount = updateSet.getInt("a");
            for (int i = 0; i < updateAmount; i++) {
                int x = updateSet.getInt("x_" + i);
                int y = updateSet.getInt("y_" + i);
                int meta = updateSet.getInt("m_" + i);
                int time = updateSet.getInt("t_" + i);

                int id = updateSet.getInt("i_" + i);
                TileState tile = this.world.getStateForId(id);

                if (tile != null) {
                    ResourceName res = new ResourceName(updateSet.getString("l_" + i));
                    TileLayer layer = RockBottomAPI.TILE_LAYER_REGISTRY.get(res);
                    if (layer != null) {
                        this.scheduleUpdate(x, y, layer, meta, time);
                    } else {
                        RockBottomAPI.logger().warning("Could not load scheduled update at " + x + ' ' + y + " with time " + time + " because layer with name " + res + " is missing!");
                    }
                } else {
                    RockBottomAPI.logger().warning("Could not load scheduled update at " + x + ' ' + y + " with time " + time + " because tile with id " + id + " is missing!");
                }
            }

            if (set.hasKey("ad_da")) {
                this.additionalData = set.getModBasedDataSet("ad_da");
            }

            this.callRetroactiveGeneration();
        } else {
            this.generate(this.world.getSortedLoopingGenerators());
            this.world.calcInitialSkylight(this.x, this.y, this.x + Constants.CHUNK_SIZE - 1, this.y + Constants.CHUNK_SIZE - 1);
        }

        this.isGenerating = false;
    }

    @Override
    public int getScheduledUpdateAmount() {
        return this.scheduledUpdates.size();
    }

    @Override
    public Biome getBiomeInner(int x, int y) {
        return this.biomeGrid[x][y];
    }

    @Override
    public Biome getMostProminentBiome() {
        return this.mostProminentBiome;
    }

    @Override
    public int getAverageHeight(TileLayer layer) {
        return this.averageHeights.getOrDefault(layer, 0);
    }

    @Override
    public float getFlatness(TileLayer layer) {
        return this.flatness.getOrDefault(layer, 1F);
    }

    @Override
    public void setBiomeInner(int x, int y, Biome biome) {
        Preconditions.checkNotNull(biome, "Tried setting null biome in chunk at " + this.gridX + ", " + this.gridY + '!');

        SetBiomeEvent event = new SetBiomeEvent(this, biome, x, y);
        if (RockBottomAPI.getEventHandler().fireEvent(event) != EventResult.CANCELLED) {
            biome = event.biome;
            x = event.x;
            y = event.y;

            Biome oldBiome = this.getBiomeInner(x, y);
            if (biome != oldBiome) {
                Counter oldCounter = this.biomeAmounts.get(oldBiome);
                if (oldCounter != null && oldCounter.get() > 0) {
                    oldCounter.add(-1);
                }

                Counter newCounter = this.biomeAmounts.computeIfAbsent(biome, b -> new Counter(0));
                newCounter.add(1);

                int highestAmount = 0;
                for (Map.Entry<Biome, Counter> entry : this.biomeAmounts.entrySet()) {
                    Counter counter = entry.getValue();
                    if (counter.get() > highestAmount) {
                        highestAmount = counter.get();
                        this.mostProminentBiome = entry.getKey();
                    }
                }
            }

            this.biomeGrid[x][y] = biome;
        }
    }

    @Override
    public List<TileLayer> getLoadedLayers() {
        return this.layersByRenderPrio;
    }

    @Override
    public List<AbstractEntityPlayer> getPlayersInRange() {
        return this.playersInRange;
    }

    @Override
    public List<AbstractEntityPlayer> getPlayersLeftRange() {
        return this.playersOutOfRangeCached;
    }

    @Override
    public Map<AbstractEntityPlayer, Counter> getLeftPlayerTimers() {
        return this.playersOutOfRangeCachedTimers;
    }

    @Override
    public int getGridX() {
        return this.gridX;
    }

    @Override
    public int getGridY() {
        return this.gridY;
    }

    @Override
    public IWorld getWorld() {
        return this.world;
    }

    @Override
    public int getX() {
        return this.x;
    }

    @Override
    public int getY() {
        return this.y;
    }

    @Override
    public int getHeightInner(TileLayer layer, int x) {
        int[] heights = this.heights.get(layer);
        return heights == null ? 0 : heights[x];
    }

    private TileState[][] getGrid(TileLayer layer, boolean create) {
        TileState[][] grid = this.stateGrid.get(layer);

        if (grid == null && create) {
            grid = new TileState[Constants.CHUNK_SIZE][Constants.CHUNK_SIZE];
            for (int x = 0; x < Constants.CHUNK_SIZE; x++) {
                for (int y = 0; y < Constants.CHUNK_SIZE; y++) {
                    grid[x][y] = GameContent.TILE_AIR.getDefState();
                }
            }

            this.stateGrid.put(layer, grid);

            this.layersByRenderPrio.add(layer);
            this.layersByRenderPrio.sort(Comparator.comparingInt(TileLayer::getRenderPriority).reversed());

            RockBottomAPI.logger().fine("Adding new tile layer " + layer + " to chunk at " + this.gridX + ", " + this.gridY);
        }

        return grid;
    }

    @Override
    public boolean hasAdditionalData() {
        return this.additionalData != null;
    }

    @Override
    public ModBasedDataSet getAdditionalData() {
        return this.additionalData;
    }

    @Override
    public void setAdditionalData(ModBasedDataSet set) {
        this.additionalData = set;
    }

    @Override
    public ModBasedDataSet getOrCreateAdditionalData() {
        if (this.additionalData == null) {
            this.additionalData = new ModBasedDataSet();
        }
        return this.additionalData;
    }

    protected static class ScheduledUpdate {

        public final int x;
        public final int y;
        public final TileLayer layer;
        public final TileState tile;
        public final int scheduledMeta;

        public int time;

        public ScheduledUpdate(int x, int y, TileLayer layer, TileState tile, int scheduledMeta, int time) {
            this.x = x;
            this.y = y;
            this.layer = layer;
            this.tile = tile;
            this.scheduledMeta = scheduledMeta;

            this.time = time;
        }
    }
}
