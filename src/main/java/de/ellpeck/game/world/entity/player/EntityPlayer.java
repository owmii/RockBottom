package de.ellpeck.game.world.entity.player;

import de.ellpeck.game.Game;
import de.ellpeck.game.data.set.DataSet;
import de.ellpeck.game.gui.GuiManager;
import de.ellpeck.game.inventory.InventoryPlayer;
import de.ellpeck.game.item.ItemInstance;
import de.ellpeck.game.render.entity.IEntityRenderer;
import de.ellpeck.game.render.entity.PlayerEntityRenderer;
import de.ellpeck.game.util.BoundBox;
import de.ellpeck.game.util.Direction;
import de.ellpeck.game.world.World;
import de.ellpeck.game.world.entity.EntityItem;
import de.ellpeck.game.world.entity.EntityLiving;

import java.util.List;
import java.util.UUID;

public class EntityPlayer extends EntityLiving{

    private final BoundBox boundingBox = new BoundBox(-0.5, -0.5, 0.5, 1.5);

    private final IEntityRenderer renderer;
    public final InventoryPlayer inv = new InventoryPlayer();

    private final UUID uniqueId;
    public final GuiManager guiManager = new GuiManager(this);

    private int respawnTimer;

    public EntityPlayer(World world, UUID uniqueId){
        super(world);
        this.renderer = new PlayerEntityRenderer();
        this.facing = Direction.RIGHT;
        this.uniqueId = uniqueId;
    }

    @Override
    public IEntityRenderer getRenderer(){
        return this.renderer;
    }

    @Override
    public void update(Game game){
        super.update(game);

        if(this.isDead()){
            this.respawnTimer++;

            if(this.respawnTimer >= 400){
                this.resetAndSpawn();
            }
        }
        else{
            List<EntityItem> entities = this.world.getEntities(this.getBoundingBox().copy().add(this.x, this.y), EntityItem.class);
            for(EntityItem entity : entities){
                if(entity.canPickUp()){
                    ItemInstance left = this.inv.addExistingFirst(entity.item, false);

                    if(left == null){
                        entity.kill();
                    }
                    else{
                        entity.item = left;
                    }
                }
            }
        }

        this.guiManager.update(game);
    }

    public void resetAndSpawn(){
        this.respawnTimer = 0;
        this.dead = false;
        this.guiManager.closeGui();

        this.motionX = 0;
        this.motionY = 0;

        this.setPos(this.world.spawnX, this.world.spawnY);
    }

    @Override
    public void onGroundHit(){
        if(this.fallAmount >= 20){
            this.health -= this.fallAmount*1.5;
        }
    }

    @Override
    public boolean shouldBeRemoved(){
        return false;
    }

    @Override
    public int getMaxHealth(){
        return 100;
    }

    @Override
    public int getRegenRate(){
        return 10;
    }

    @Override
    public BoundBox getBoundingBox(){
        return this.boundingBox;
    }

    @Override
    public void save(DataSet set){
        super.save(set);
        this.inv.save(set);
    }

    @Override
    public void load(DataSet set){
        super.load(set);
        this.inv.load(set);
    }

    public UUID getUniqueId(){
        return this.uniqueId;
    }
}
