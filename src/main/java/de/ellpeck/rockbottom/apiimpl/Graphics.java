package de.ellpeck.rockbottom.apiimpl;

import de.ellpeck.rockbottom.api.IGameInstance;
import de.ellpeck.rockbottom.api.IGraphics;
import de.ellpeck.rockbottom.api.RockBottomAPI;
import de.ellpeck.rockbottom.api.assets.IAssetManager;
import de.ellpeck.rockbottom.api.assets.ITexture;
import de.ellpeck.rockbottom.api.assets.font.FormattingCode;
import de.ellpeck.rockbottom.api.assets.font.IFont;
import de.ellpeck.rockbottom.api.construction.resource.ResInfo;
import de.ellpeck.rockbottom.api.construction.resource.ResourceRegistry;
import de.ellpeck.rockbottom.api.data.settings.Settings;
import de.ellpeck.rockbottom.api.event.EventResult;
import de.ellpeck.rockbottom.api.event.impl.TooltipEvent;
import de.ellpeck.rockbottom.api.gui.Gui;
import de.ellpeck.rockbottom.api.item.Item;
import de.ellpeck.rockbottom.api.item.ItemInstance;
import de.ellpeck.rockbottom.api.render.item.IItemRenderer;
import de.ellpeck.rockbottom.api.util.Colors;
import de.ellpeck.rockbottom.api.util.reg.IResourceName;
import de.ellpeck.rockbottom.init.RockBottom;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;
import org.newdawn.slick.opengl.TextureImpl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class Graphics implements IGraphics{

    private static final IResourceName SLOT_NAME = RockBottomAPI.createInternalRes("gui.slot");
    private final RockBottom game;

    public boolean isDebug;
    public boolean isItemInfoDebug;
    public boolean isChunkBorderDebug;
    public boolean isGuiDebug;
    private float displayRatio;
    private float guiScale;
    private float worldScale;
    private float guiWidth;
    private float guiHeight;
    private float worldWidth;
    private float worldHeight;


    public Graphics(RockBottom game){
        this.game = game;
    }

    @Override
    public void renderSlotInGui(IGameInstance game, IAssetManager manager, ItemInstance slot, float x, float y, float scale, boolean hovered){
        ITexture texture = manager.getTexture(SLOT_NAME);

        int color = game.getSettings().guiColor;
        if(!hovered){
            color = Colors.multiply(color, 0.75F);
        }

        texture.draw(x, y, texture.getWidth()*scale, texture.getHeight()*scale, color);

        if(slot != null){
            this.renderItemInGui(game, manager, slot, x+3F*scale, y+3F*scale, scale, Colors.WHITE);
        }
    }


    @Override
    public void renderItemInGui(IGameInstance game, IAssetManager manager, ItemInstance slot, float x, float y, float scale, int color){
        Item item = slot.getItem();
        IItemRenderer renderer = item.getRenderer();
        if(renderer != null){
            renderer.render(game, manager, this, item, slot, x, y, 12F*scale, color);
        }

        if(slot.getAmount() > 1){
            manager.getFont().drawStringFromRight(x+15F*scale, y+9F*scale, String.valueOf(slot.getAmount()), 0.25F*scale);
        }
    }

    @Override
    public void describeItem(IGameInstance game, IAssetManager manager, ItemInstance instance){
        boolean advanced = Settings.KEY_ADVANCED_INFO.isDown();

        List<String> desc = new ArrayList<>();
        instance.getItem().describeItem(manager, instance, desc, advanced);

        if(this.isItemInfoDebug()){
            desc.add("");
            desc.add(FormattingCode.GRAY+"Name: "+instance.getItem().getName().toString());
            desc.add(FormattingCode.GRAY+"Meta: "+instance.getMeta());
            desc.add(FormattingCode.GRAY+"Data: "+instance.getAdditionalData());
            desc.add(FormattingCode.GRAY+"Max Amount: "+instance.getMaxAmount());
            desc.add(FormattingCode.GRAY+"Resources: "+ResourceRegistry.getNames(new ResInfo(instance)));
        }

        if(RockBottomAPI.getEventHandler().fireEvent(new TooltipEvent(instance, game, manager, this, desc)) != EventResult.CANCELLED){
            this.drawHoverInfoAtMouse(game, manager, true, 500, desc);
        }
    }

    @Override
    public void drawHoverInfoAtMouse(IGameInstance game, IAssetManager manager, boolean firstLineOffset, int maxLength, String... text){
        this.drawHoverInfoAtMouse(game, manager, firstLineOffset, maxLength, Arrays.asList(text));
    }

    @Override
    public void drawHoverInfoAtMouse(IGameInstance game, IAssetManager manager, boolean firstLineOffset, int maxLength, List<String> text){
        float mouseX = this.getMouseInGuiX();
        float mouseY = this.getMouseInGuiY();

        this.drawHoverInfo(game, manager, mouseX+18F/this.getGuiScale(), mouseY+18F/this.getGuiScale(), 0.25F, firstLineOffset, false, maxLength, text);
    }

    @Override
    public void drawHoverInfo(IGameInstance game, IAssetManager manager, float x, float y, float scale, boolean firstLineOffset, boolean canLeaveScreen, int maxLength, List<String> text){
        IFont font = manager.getFont();

        float boxWidth = 0F;
        float boxHeight = 0F;

        if(maxLength > 0){
            text = font.splitTextToLength(maxLength, scale, true, text);
        }

        for(String s : text){
            float length = font.getWidth(s, scale);
            if(length > boxWidth){
                boxWidth = length;
            }

            if(firstLineOffset && boxHeight == 0F && text.size() > 1){
                boxHeight += 3F;
            }
            boxHeight += font.getHeight(scale);
        }

        if(boxWidth > 0F && boxHeight > 0F){
            boxWidth += 4F;
            boxHeight += 4F;

            if(!canLeaveScreen){
                x = Math.max(0, Math.min(x, this.getWidthInGui()-boxWidth));
                y = Math.max(0, Math.min(y, this.getHeightInGui()-boxHeight));
            }

            this.fillRect(x, y, boxWidth, boxHeight, Gui.HOVER_INFO_BACKGROUND);
            this.drawRect(x, y, boxWidth, boxHeight, Colors.BLACK);

            float yOffset = 0F;
            for(String s : text){
                font.drawString(x+2F, y+2F+yOffset, s, scale);

                if(firstLineOffset && yOffset == 0F){
                    yOffset += 3F;
                }
                yOffset += font.getHeight(scale);
            }
        }
    }

    @Override
    public void pushMatrix(){
        GL11.glPushMatrix();
    }

    @Override
    public void popMatrix(){
        GL11.glPopMatrix();
    }

    @Override
    public void bindColor(int color){
        Colors.bind(color);
    }

    @Override
    public void bindColor(float r, float g, float b, float a){
        Colors.bind(r, g, b, a);
    }

    @Override
    public void backgroundColor(int color){
        this.backgroundColor(Colors.getR(color), Colors.getG(color), Colors.getB(color), Colors.getA(color));
    }

    @Override
    public void backgroundColor(float r, float g, float b, float a){
        GL11.glClearColor(r, g, b, a);
    }

    @Override
    public void scale(float scaleX, float scaleY){
        GL11.glScalef(scaleX, scaleY, 1F);
    }

    @Override
    public void translate(float x, float y){
        GL11.glTranslatef(x, y, 0F);
    }

    @Override
    public void rotate(float angle){
        GL11.glRotatef(angle, 0F, 0F, 1F);
    }

    @Override
    public void drawRect(float x, float y, float width, float height, int color){
        this.drawRect(x, y, width, height, 1F, color);
    }

    @Override
    public void drawRect(float x, float y, float width, float height, float lineWidth, int color){
        this.fillRect(x, y, width-lineWidth, lineWidth, color);
        this.fillRect(x+lineWidth, y+height-lineWidth, width-lineWidth, lineWidth, color);
        this.fillRect(x+width-lineWidth, y, lineWidth, height-lineWidth, color);
        this.fillRect(x, y+lineWidth, lineWidth, height-lineWidth, color);
    }

    @Override
    public void fillRect(float x, float y, float width, float height, int color){
        TextureImpl.bindNone();
        this.bindColor(color);

        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(x, y);
        GL11.glVertex2f(x+width, y);
        GL11.glVertex2f(x+width, y+height);
        GL11.glVertex2f(x, y+height);
        GL11.glEnd();
    }

    @Override
    public void calcScales(){
        RockBottomAPI.logger().config("Calculating render scales");

        float width = Display.getWidth();
        float height = Display.getHeight();

        this.displayRatio = Math.min(width/16F, height/9F);

        this.guiScale = (this.getDisplayRatio()/20F)*this.game.getSettings().guiScale;
        this.guiWidth = width/this.guiScale;
        this.guiHeight = height/this.guiScale;

        this.worldScale = this.getDisplayRatio()*this.game.getSettings().renderScale;
        this.worldWidth = width/this.worldScale;
        this.worldHeight = height/this.worldScale;

        RockBottomAPI.logger().config("Successfully calculated render scales");
    }

    @Override
    public float getDisplayRatio(){
        return this.displayRatio;
    }

    @Override
    public float getGuiScale(){
        return this.guiScale;
    }

    @Override
    public float getWorldScale(){
        return this.worldScale;
    }

    @Override
    public float getWidthInWorld(){
        return this.worldWidth;
    }

    @Override
    public float getHeightInWorld(){
        return this.worldHeight;
    }

    @Override
    public float getWidthInGui(){
        return this.guiWidth;
    }

    @Override
    public float getHeightInGui(){
        return this.guiHeight;
    }

    @Override
    public float getMouseInGuiX(){
        return (float)this.game.getInput().getMouseX()/this.getGuiScale();
    }

    @Override
    public float getMouseInGuiY(){
        return (float)this.game.getInput().getMouseY()/this.getGuiScale();
    }

    @Override
    public boolean isDebug(){
        return this.isDebug;
    }

    @Override
    public boolean isItemInfoDebug(){
        return this.isItemInfoDebug;
    }

    @Override
    public boolean isChunkBorderDebug(){
        return this.isChunkBorderDebug;
    }

    @Override
    public boolean isGuiDebug(){
        return this.isGuiDebug;
    }

    @Override
    public double getMousedTileX(){
        double mouseX = this.game.getInput().getMouseX();
        double worldAtScreenX = this.game.getPlayer().x-this.getWidthInWorld()/2;
        return worldAtScreenX+mouseX/(double)this.getWorldScale();
    }

    @Override
    public double getMousedTileY(){
        double mouseY = this.game.getInput().getMouseY();
        double worldAtScreenY = -this.game.getPlayer().y-this.getHeightInWorld()/2;
        return -(worldAtScreenY+mouseY/(double)this.getWorldScale())+1;
    }
}
