package de.ellpeck.rockbottom.world;

import de.ellpeck.rockbottom.ContentRegistry;
import de.ellpeck.rockbottom.RockBottom;
import de.ellpeck.rockbottom.api.Constants;
import de.ellpeck.rockbottom.api.RockBottomAPI;
import de.ellpeck.rockbottom.api.data.set.DataSet;
import de.ellpeck.rockbottom.api.entity.Entity;
import de.ellpeck.rockbottom.api.entity.player.AbstractEntityPlayer;
import de.ellpeck.rockbottom.api.event.EventResult;
import de.ellpeck.rockbottom.api.event.impl.WorldTickEvent;
import de.ellpeck.rockbottom.api.tile.Tile;
import de.ellpeck.rockbottom.api.tile.entity.TileEntity;
import de.ellpeck.rockbottom.api.util.BoundBox;
import de.ellpeck.rockbottom.api.util.Direction;
import de.ellpeck.rockbottom.api.util.MutableInt;
import de.ellpeck.rockbottom.api.util.Pos2;
import de.ellpeck.rockbottom.api.util.reg.IResourceName;
import de.ellpeck.rockbottom.api.util.reg.NameToIndexInfo;
import de.ellpeck.rockbottom.api.world.IChunk;
import de.ellpeck.rockbottom.api.world.IWorld;
import de.ellpeck.rockbottom.api.world.TileLayer;
import de.ellpeck.rockbottom.api.world.WorldInfo;
import de.ellpeck.rockbottom.net.packet.toclient.PacketEntityChange;
import de.ellpeck.rockbottom.net.packet.toclient.PacketParticles;
import de.ellpeck.rockbottom.net.server.ConnectedPlayer;
import de.ellpeck.rockbottom.api.util.Util;
import de.ellpeck.rockbottom.world.entity.player.EntityPlayer;
import io.netty.channel.Channel;
import org.newdawn.slick.util.Log;

import java.io.File;
import java.util.*;
import java.util.function.Predicate;

public class World implements IWorld{

    public final Random generatorRandom = new Random();

    public final List<IChunk> loadedChunks = new ArrayList<>();
    protected final Map<Pos2, IChunk> chunkLookup = new HashMap<>();
    protected final WorldInfo info;
    public List<EntityPlayer> players = new ArrayList<>();
    public NameToIndexInfo tileRegInfo;
    protected File chunksDirectory;
    protected File playerDirectory;
    protected int saveTicksCounter;

    public World(WorldInfo info, NameToIndexInfo tileRegInfo){
        this.info = info;
        this.tileRegInfo = tileRegInfo;
        this.generatorRandom.setSeed(this.info.seed);
    }

    public void initFiles(File worldDirectory){
        this.chunksDirectory = new File(worldDirectory, "chunks");
        this.playerDirectory = new File(worldDirectory, "players");
    }

    protected void checkListSync(){
        if(this.loadedChunks.size() != this.chunkLookup.size()){
            throw new IllegalStateException("LoadedChunks and ChunkLookup are out of sync!");
        }
    }

    public void update(RockBottom game){
        this.checkListSync();

        if(RockBottomAPI.getEventHandler().fireEvent(new WorldTickEvent(this)) != EventResult.CANCELLED){
            for(int i = 0; i < this.loadedChunks.size(); i++){
                IChunk chunk = this.loadedChunks.get(i);
                chunk.update(game);

                if(chunk.shouldUnload()){
                    this.unloadChunk(chunk);
                    i--;
                }
            }

            this.info.totalTimeInWorld++;

            this.info.currentWorldTime++;
            if(this.info.currentWorldTime >= Constants.TIME_PER_DAY){
                this.info.currentWorldTime = 0;
            }

            this.saveTicksCounter++;
            if(this.saveTicksCounter >= game.getSettings().autosaveIntervalSeconds*Constants.TARGET_TPS){
                this.saveTicksCounter = 0;

                this.save();
            }
        }
    }

    @Override
    public void addEntity(Entity entity){
        IChunk chunk = this.getChunk(entity.x, entity.y);
        chunk.addEntity(entity);

        if(entity instanceof EntityPlayer){
            EntityPlayer player = (EntityPlayer)entity;

            if(this.getPlayer(player.getUniqueId()) == null){
                this.players.add(player);
            }
            else{
                Log.error("Tried adding player "+player+" with id "+player.getUniqueId()+" to world that already contained it!");
            }
        }

        if(RockBottomAPI.getNet().isServer()){
            RockBottomAPI.getNet().sendToAllPlayersExcept(this, new PacketEntityChange(entity, false), entity);
        }
    }

    @Override
    public void addTileEntity(TileEntity tile){
        IChunk chunk = this.getChunk(tile.x, tile.y);
        chunk.addTileEntity(tile);
    }

    @Override
    public void removeEntity(Entity entity){
        IChunk chunk = this.getChunk(entity.x, entity.y);
        chunk.removeEntity(entity);

        if(entity instanceof EntityPlayer){
            this.players.remove(entity);
        }

        entity.onRemoveFromWorld();

        if(RockBottomAPI.getNet().isServer()){
            RockBottomAPI.getNet().sendToAllPlayersExcept(this, new PacketEntityChange(entity, true), entity);
        }
    }

    @Override
    public void removeTileEntity(int x, int y){
        IChunk chunk = this.getChunk(x, y);
        chunk.removeTileEntity(x, y);
    }

    @Override
    public TileEntity getTileEntity(int x, int y){
        IChunk chunk = this.getChunk(x, y);
        return chunk.getTileEntity(x, y);
    }

    @Override
    public <T extends TileEntity> T getTileEntity(int x, int y, Class<T> tileClass){
        IChunk chunk = this.getChunk(x, y);
        return chunk.getTileEntity(x, y, tileClass);
    }

    @Override
    public List<Entity> getAllEntities(){
        List<Entity> entities = new ArrayList<>();
        for(IChunk chunk : this.loadedChunks){
            entities.addAll(chunk.getAllEntities());
        }
        return entities;
    }

    @Override
    public List<TileEntity> getAllTileEntities(){
        List<TileEntity> tiles = new ArrayList<>();
        for(IChunk chunk : this.loadedChunks){
            tiles.addAll(chunk.getAllTileEntities());
        }
        return tiles;
    }

    @Override
    public Entity getEntity(UUID id){
        for(IChunk chunk : this.loadedChunks){
            Entity entity = chunk.getEntity(id);
            if(entity != null){
                return entity;
            }
        }
        return null;
    }

    @Override
    public List<Entity> getEntities(BoundBox area){
        return this.getEntities(area, null, null);
    }

    @Override
    public List<Entity> getEntities(BoundBox area, Predicate<Entity> test){
        return this.getEntities(area, null, test);
    }

    @Override
    public <T extends Entity> List<T> getEntities(BoundBox area, Class<T> type){
        return this.getEntities(area, type, null);
    }

    @Override
    public <T extends Entity> List<T> getEntities(BoundBox area, Class<T> type, Predicate<T> test){
        int minChunkX = Util.toGridPos(area.getMinX())-1;
        int minChunkY = Util.toGridPos(area.getMinY())-1;
        int maxChunkX = Util.toGridPos(area.getMaxX())+1;
        int maxChunkY = Util.toGridPos(area.getMaxY())+1;

        List<T> entities = new ArrayList<>();
        for(int x = minChunkX; x <= maxChunkX; x++){
            for(int y = minChunkY; y <= maxChunkY; y++){
                IChunk chunk = this.getChunkFromGridCoords(x, y);
                entities.addAll(chunk.getEntities(area, type, test));
            }
        }
        return entities;
    }

    @Override
    public List<BoundBox> getCollisions(BoundBox area){
        List<BoundBox> collisions = new ArrayList<>();

        for(int x = Util.floor(area.getMinX()); x <= Util.ceil(area.getMaxX()); x++){
            for(int y = Util.floor(area.getMinY()); y <= Util.ceil(area.getMaxY()); y++){
                if(this.isPosLoaded(x, y)){
                    Tile tile = this.getTile(x, y);

                    BoundBox box = tile.getBoundBox(this, x, y);
                    if(box != null && !box.isEmpty()){
                        collisions.add(box.copy().add(x, y));
                    }
                }
            }
        }

        return collisions;
    }

    @Override
    public byte getCombinedLight(int x, int y){
        IChunk chunk = this.getChunk(x, y);
        return chunk.getCombinedLight(x, y);
    }

    @Override
    public byte getSkyLight(int x, int y){
        IChunk chunk = this.getChunk(x, y);
        return chunk.getSkyLight(x, y);
    }

    @Override
    public byte getArtificialLight(int x, int y){
        IChunk chunk = this.getChunk(x, y);
        return chunk.getArtificialLight(x, y);
    }

    @Override
    public void setSkyLight(int x, int y, byte light){
        IChunk chunk = this.getChunk(x, y);
        chunk.setSkyLight(x, y, light);
    }

    @Override
    public void setArtificialLight(int x, int y, byte light){
        IChunk chunk = this.getChunk(x, y);
        chunk.setArtificialLight(x, y, light);
    }

    @Override
    public boolean isChunkLoaded(int x, int y){
        return this.chunkLookup.containsKey(new Pos2(x, y));
    }

    @Override
    public boolean isPosLoaded(int x, int y){
        return this.isChunkLoaded(Util.toGridPos(x), Util.toGridPos(y));
    }

    @Override
    public void scheduleUpdate(int x, int y, TileLayer layer, int time){
        IChunk chunk = this.getChunk(x, y);
        chunk.scheduleUpdate(x, y, layer, time);
    }

    @Override
    public void setDirty(int x, int y){
        IChunk chunk = this.getChunk(x, y);
        chunk.setDirty(x, y);
    }

    @Override
    public int getLowestAirUpwards(TileLayer layer, int x, int y){
        IChunk chunk = this.getChunk(x, y);
        return chunk.getLowestAirUpwards(layer, x, y);
    }

    @Override
    public int getIdForTile(Tile tile){
        IResourceName name = RockBottomAPI.TILE_REGISTRY.getId(tile);
        if(name != null){
            return this.tileRegInfo.getId(name);
        }
        else{
            return -1;
        }
    }

    @Override
    public Tile getTileForId(int id){
        IResourceName name = this.tileRegInfo.get(id);
        return RockBottomAPI.TILE_REGISTRY.get(name);
    }

    @Override
    public WorldInfo getWorldInfo(){
        return this.info;
    }

    @Override
    public NameToIndexInfo getTileRegInfo(){
        return this.tileRegInfo;
    }

    @Override
    public IChunk getChunk(double x, double y){
        return this.getChunkFromGridCoords(Util.toGridPos(x), Util.toGridPos(y));
    }

    @Override
    public IChunk getChunkFromGridCoords(int gridX, int gridY){
        IChunk chunk = this.chunkLookup.get(new Pos2(gridX, gridY));

        if(chunk == null){
            chunk = this.loadChunk(gridX, gridY);
        }

        return chunk;
    }

    protected Chunk loadChunk(int gridX, int gridY){
        Chunk chunk = new Chunk(this, gridX, gridY);
        this.loadedChunks.add(chunk);
        this.chunkLookup.put(new Pos2(gridX, gridY), chunk);

        DataSet set = new DataSet();
        set.read(new File(this.chunksDirectory, "c_"+gridX+"_"+gridY+".dat"));
        chunk.loadOrCreate(set);

        return chunk;
    }

    @Override
    public void unloadChunk(IChunk chunk){
        this.saveChunk(chunk);

        this.loadedChunks.remove(chunk);
        this.chunkLookup.remove(new Pos2(chunk.getGridX(), chunk.getGridY()));
    }

    @Override
    public Tile getTile(int x, int y){
        return this.getTile(TileLayer.MAIN, x, y);
    }

    @Override
    public Tile getTile(TileLayer layer, int x, int y){
        IChunk chunk = this.getChunk(x, y);
        return chunk.getTile(layer, x, y);
    }

    @Override
    public int getMeta(int x, int y){
        return this.getMeta(TileLayer.MAIN, x, y);
    }

    @Override
    public int getMeta(TileLayer layer, int x, int y){
        IChunk chunk = this.getChunk(x, y);
        return chunk.getMeta(layer, x, y);
    }

    @Override
    public void setTile(int x, int y, Tile tile){
        this.setTile(TileLayer.MAIN, x, y, tile);
    }

    @Override
    public void setTile(TileLayer layer, int x, int y, Tile tile){
        this.setTile(layer, x, y, tile, 0);
    }

    @Override
    public void setTile(int x, int y, Tile tile, int meta){
        this.setTile(TileLayer.MAIN, x, y, tile, meta);
    }

    @Override
    public void setTile(TileLayer layer, int x, int y, Tile tile, int meta){
        IChunk chunk = this.getChunk(x, y);
        chunk.setTile(layer, x, y, tile, meta);
    }

    @Override
    public void setMeta(int x, int y, int meta){
        this.setMeta(TileLayer.MAIN, x, y, meta);
    }

    @Override
    public void setMeta(TileLayer layer, int x, int y, int meta){
        IChunk chunk = this.getChunk(x, y);
        chunk.setMeta(layer, x, y, meta);
    }

    @Override
    public void notifyNeighborsOfChange(int x, int y, TileLayer layer){
        for(Direction direction : Direction.ADJACENT){
            int offX = x+direction.x;
            int offY = y+direction.y;

            this.getTile(layer, offX, offY).onChangeAround(this, offX, offY, layer, x, y, layer);
        }

        TileLayer opp = layer.getOpposite();
        this.getTile(opp, x, y).onChangeAround(this, x, y, opp, x, y, layer);
    }

    public void save(){
        long timeStarted = System.currentTimeMillis();
        Log.info("Saving world...");

        for(IChunk chunk : this.loadedChunks){
            this.saveChunk(chunk);
        }

        this.info.save();

        for(EntityPlayer player : this.players){
            this.savePlayer(player);
        }

        Log.info("Finished saving world, took "+(System.currentTimeMillis()-timeStarted)+"ms.");
    }

    @Override
    public void savePlayer(AbstractEntityPlayer player){
        DataSet playerSet = new DataSet();
        player.save(playerSet);

        playerSet.write(new File(this.playerDirectory, player.getUniqueId().toString()+".dat"));
    }

    @Override
    public EntityPlayer createPlayer(UUID id, Channel channel){
        EntityPlayer player = channel != null ? new ConnectedPlayer(this, id, channel) : new EntityPlayer(this, id);

        File file = new File(this.playerDirectory, id+".dat");
        if(file.exists()){
            DataSet set = new DataSet();
            set.read(file);

            player.load(set);
            Log.info("Loading player with unique id "+id+"!");
        }
        else{
            player.resetAndSpawn(RockBottom.get());
            Log.info("Adding new player with unique id "+id+" to world!");
        }
        return player;
    }

    @Override
    public EntityPlayer getPlayer(UUID id){
        for(EntityPlayer player : this.players){
            if(id.equals(player.getUniqueId())){
                return player;
            }
        }
        return null;
    }

    protected void saveChunk(IChunk chunk){
        if(chunk.needsSave()){
            DataSet set = new DataSet();
            chunk.save(set);

            set.write(new File(this.chunksDirectory, "c_"+chunk.getGridX()+"_"+chunk.getGridY()+".dat"));
        }
    }

    @Override
    public void destroyTile(int x, int y, TileLayer layer, Entity destroyer, boolean shouldDrop){
        Tile tile = this.getTile(layer, x, y);
        int meta = this.getMeta(x, y);

        tile.onDestroyed(this, x, y, destroyer, layer, shouldDrop);

        if(RockBottomAPI.getNet().isServer()){
            RockBottomAPI.getNet().sendToAllPlayers(this, PacketParticles.tile(this, x, y, tile, meta));
        }

        RockBottom.get().getParticleManager().addTileParticles(this, x, y, tile, meta);

        this.setTile(layer, x, y, ContentRegistry.TILE_AIR);
    }

    @Override
    public int getSpawnX(){
        return 0;
    }

    @Override
    public void causeLightUpdate(int x, int y){
        MutableInt recurseCount = new MutableInt(0);
        this.causeLightUpdate(x, y, recurseCount);

        if(recurseCount.get() >= 100){
            Log.debug("Updated light at "+x+", "+y+" using "+recurseCount.get()+" recursive calls!");
        }
    }

    protected void causeLightUpdate(int x, int y, MutableInt recurseCount){
        if(recurseCount.get() > 5000){
            Log.warn("UPDATING LIGHT AT "+x+", "+y+" TOOK MORE THAN 5000 RECURSIVE CALLS! ABORTING!");
            return;
        }

        for(Direction direction : Direction.SURROUNDING_INCLUDING_NONE){
            int dirX = x+direction.x;
            int dirY = y+direction.y;

            if(this.isPosLoaded(dirX, dirY)){
                boolean change = false;

                byte skylightThere = this.getSkyLight(dirX, dirY);
                byte calcedSkylight = this.calcLight(dirX, dirY, true);
                if(calcedSkylight != skylightThere){
                    this.setSkyLight(dirX, dirY, calcedSkylight);
                    change = true;
                }

                byte artLightThere = this.getArtificialLight(dirX, dirY);
                byte calcedArtLight = this.calcLight(dirX, dirY, false);
                if(calcedArtLight != artLightThere){
                    this.setArtificialLight(dirX, dirY, calcedArtLight);
                    change = true;
                }

                if(change){
                    this.causeLightUpdate(dirX, dirY, recurseCount.add(1));
                }
            }
        }
    }

    public void calcInitialSkylight(int x1, int y1, int x2, int y2){
        for(int x = x2; x >= x1; x--){
            for(int y = y2; y >= y1; y--){
                byte light = this.calcLight(x, y, true);
                this.setSkyLight(x, y, light);
            }
        }

        for(int x = x1; x <= x2; x++){
            for(int y = y1; y <= y2; y++){
                byte light = this.calcLight(x, y, true);
                this.setSkyLight(x, y, light);
            }
        }
    }

    protected byte calcLight(int x, int y, boolean isSky){
        byte maxLight = 0;

        for(Direction direction : Direction.SURROUNDING){
            int dirX = x+direction.x;
            int dirY = y+direction.y;

            if(this.isPosLoaded(dirX, dirY)){
                byte light = isSky ? this.getSkyLight(dirX, dirY) : this.getArtificialLight(dirX, dirY);
                if(light > maxLight){
                    maxLight = light;
                }
            }
        }

        maxLight *= this.getTileModifier(x, y, isSky);

        byte emitted = this.getTileLight(x, y, isSky);
        if(emitted > maxLight){
            maxLight = emitted;
        }

        return (byte)Math.min(Constants.MAX_LIGHT, maxLight);
    }

    protected byte getTileLight(int x, int y, boolean isSky){
        Tile foreground = this.getTile(x, y);
        Tile background = this.getTile(TileLayer.BACKGROUND, x, y);

        if(foreground.isAir() && background.isAir()){
            if(isSky){
                return Constants.MAX_LIGHT;
            }
        }
        else{
            if(!isSky){
                int foregroundLight = foreground.getLight(this, x, y, TileLayer.MAIN);
                int backgroundLight = background.getLight(this, x, y, TileLayer.BACKGROUND);
                return (byte)Math.max(foregroundLight, backgroundLight);
            }
        }
        return 0;
    }

    protected float getTileModifier(int x, int y, boolean isSky){
        Tile foreground = this.getTile(x, y);

        if(!foreground.isAir()){
            return foreground.getTranslucentModifier(this, x, y, TileLayer.MAIN);
        }
        else{
            Tile background = this.getTile(TileLayer.BACKGROUND, x, y);
            if(!background.isAir()){
                return background.getTranslucentModifier(this, x, y, TileLayer.BACKGROUND);
            }
            else{
                return isSky ? 1.0F : 0.8F;
            }
        }
    }

    public float getSkylightModifier(){
        int noon = Constants.TIME_PER_DAY/2;
        if(this.info.currentWorldTime <= noon){
            return (float)this.info.currentWorldTime/(float)noon;
        }
        else{
            return 1F-(float)(this.info.currentWorldTime-noon)/(float)noon;
        }
    }
}
