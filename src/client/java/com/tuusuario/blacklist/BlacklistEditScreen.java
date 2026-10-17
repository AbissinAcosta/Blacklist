package com.tuusuario.blacklist; // <- ajusta al paquete de tu proyecto

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Editor de posiciones: arrastra con el ratón el botón del chat, las alertas, el tracker de
 * eventos y el profit de cofres. La rueda del ratón sobre un elemento cambia su tamaño.
 * Se abre con /blacklistmod gui (o desde Ajustes / QoL) y guarda al soltar el ratón.
 *
 * La posición del ratón se toma de extractRenderState (que ya la recibe), así los
 * métodos de ratón solo necesitan saber "hubo un clic / arrastre / soltar" y no
 * dependen de los detalles de MouseButtonEvent.
 */
public class BlacklistEditScreen extends Screen {

    private enum Target { NONE, BUTTON, HUD, EVENTS, PROFIT, CHAT }

    private static final int WHITE = 0xFFFFFFFF;
    private static final int GRAY = 0xFFAAAAAA;
    private static final int ACCENT = 0xFF55AAFF;
    private static final int BORDER = 0xFF3A3A3A;
    private static final int BORDER_OFF = 0xFF7A2E2E; // elemento desactivado
    private static final int CARD = 0xFF2B2B2B;

    private final Screen parent; // puede ser null: al cerrar vuelve al juego
    private final BlacklistConfig config;

    private Target dragging = Target.NONE;
    private int grabX;
    private int grabY;
    private int mouseXNow;
    private int mouseYNow;

    public BlacklistEditScreen(Screen parent) {
        super(Component.literal("Mover elementos"));
        this.parent = parent;
        this.config = BlacklistClient.get().getConfig();
    }

    // ------------------------------------------------------------ geometría

    private int buttonX() {
        return config.buttonX(width);
    }

    private int buttonY() {
        return config.buttonY(height);
    }

    private int hudW() {
        return (int) Math.ceil(BlacklistHud.previewWidth(font) * config.hudScale);
    }

    private int hudH() {
        return (int) Math.ceil(BlacklistHud.previewHeight() * config.hudScale);
    }

    private int eventsW() {
        return (int) Math.ceil(EventsHud.previewWidth(font) * config.eventsScale);
    }

    private int eventsH() {
        return (int) Math.ceil(EventsHud.previewHeight() * config.eventsScale);
    }

    private int profitW() {
        return (int) Math.ceil(ProfitHud.previewWidth(font) * config.profitScale);
    }

    private int profitH() {
        return (int) Math.ceil(ProfitHud.previewHeight() * config.profitScale);
    }

    private int chatW() {
        return (int) Math.ceil(ChatToastHud.previewWidth(font) * config.chatToastScale);
    }

    private int chatH() {
        return (int) Math.ceil(ChatToastHud.previewHeight() * config.chatToastScale);
    }

    private void keepChatOnScreen() {
        config.chatToastX = clamp(config.chatToastX, 0, width - chatW());
        config.chatToastY = clamp(config.chatToastY, 0, height - chatH());
    }

    private void keepHudOnScreen() {
        config.hudX = clamp(config.hudX, 0, width - hudW());
        config.hudY = clamp(config.hudY, 0, height - hudH());
    }

    private void keepEventsOnScreen() {
        config.eventsX = clamp(config.eventsX, 0, width - eventsW());
        config.eventsY = clamp(config.eventsY, 0, height - eventsH());
    }

    private void keepProfitOnScreen() {
        config.profitX = clamp(config.profitX, 0, width - profitW());
        config.profitY = clamp(config.profitY, 0, height - profitH());
    }

    private static boolean inside(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(Math.max(min, max), value));
    }

    /** ¿Está el ratón sobre este elemento? (los textos tienen 2 px de margen). */
    private boolean hit(Target t, int mx, int my) {
        return switch (t) {
            case BUTTON -> inside(mx, my, buttonX(), buttonY(), config.buttonSize, config.buttonSize);
            case HUD -> inside(mx, my, config.hudX - 2, config.hudY - 2, hudW() + 4, hudH() + 4);
            case EVENTS -> inside(mx, my, config.eventsX - 2, config.eventsY - 2, eventsW() + 4, eventsH() + 4);
            case PROFIT -> inside(mx, my, config.profitX - 2, config.profitY - 2, profitW() + 4, profitH() + 4);
            case CHAT -> inside(mx, my, config.chatToastX - 2, config.chatToastY - 2, chatW() + 4, chatH() + 4);
            case NONE -> false;
        };
    }

    /** Elemento bajo el ratón (si se solapan, gana el de esta lista, de arriba abajo). */
    private Target targetAt(int mx, int my) {
        for (Target t : new Target[]{Target.BUTTON, Target.CHAT, Target.PROFIT, Target.EVENTS, Target.HUD}) {
            if (hit(t, mx, my)) return t;
        }
        return Target.NONE;
    }

    // ----------------------------------------------------------------- init

    @Override
    protected void init() {
        int y = height - 56; // por encima de la barra del chat de referencia

        addRenderableWidget(Button.builder(Component.literal("Listo"), b -> onClose())
                .bounds(width / 2 - 105, y, 100, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Restablecer"), b -> {
            config.resetPositions();
            config.save();
        }).bounds(width / 2 + 5, y, 100, 20).build());
    }

    @Override
    public void onClose() {
        config.save();
        minecraft.setScreen(parent);
    }

    // ---------------------------------------------------------------- ratón

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        // Primero los botones normales (Listo / Restablecer)
        if (super.mouseClicked(event, doubleClick)) return true;

        int mx = mouseXNow;
        int my = mouseYNow;

        Target t = targetAt(mx, my);
        if (t == Target.NONE) return false;

        dragging = t;
        switch (t) {
            case BUTTON -> {
                grabX = mx - buttonX();
                grabY = my - buttonY();
            }
            case HUD -> {
                grabX = mx - config.hudX;
                grabY = my - config.hudY;
            }
            case EVENTS -> {
                grabX = mx - config.eventsX;
                grabY = my - config.eventsY;
            }
            case PROFIT -> {
                grabX = mx - config.profitX;
                grabY = my - config.profitY;
            }
            case CHAT -> {
                grabX = mx - config.chatToastX;
                grabY = my - config.chatToastY;
            }
            case NONE -> {
            }
        }
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        // El movimiento en sí se aplica en extractRenderState con la posición actual
        if (dragging != Target.NONE) return true;
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (dragging != Target.NONE) {
            dragging = Target.NONE;
            config.save();
            return true;
        }
        return super.mouseReleased(event);
    }

    /** Rueda del ratón sobre un elemento: arriba = más grande, abajo = más pequeño. */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY == 0) return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);

        int mx = (int) mouseX;
        int my = (int) mouseY;
        int notches = scrollY > 0 ? 1 : -1;

        Target t = dragging != Target.NONE ? dragging : targetAt(mx, my);
        if (t == Target.NONE) return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);

        switch (t) {
            case BUTTON -> config.resizeButton(notches * 2, width, height);
            case HUD -> {
                config.scaleHud(notches * 0.1f);
                keepHudOnScreen();
            }
            case EVENTS -> {
                config.scaleEvents(notches * 0.1f);
                keepEventsOnScreen();
            }
            case PROFIT -> {
                config.scaleProfit(notches * 0.1f);
                keepProfitOnScreen();
            }
            case CHAT -> {
                config.scaleChatToast(notches * 0.1f);
                keepChatOnScreen();
            }
            case NONE -> {
            }
        }
        config.save();
        return true;
    }

    private void applyDrag(int mx, int my) {
        int nx = mx - grabX;
        int ny = my - grabY;

        switch (dragging) {
            case BUTTON -> {
                nx = clamp(nx, 0, width - config.buttonSize);
                ny = clamp(ny, 0, height - config.buttonSize);
                config.buttonFromRight = width - nx;
                config.buttonFromBottom = height - ny;
            }
            case HUD -> {
                config.hudX = clamp(nx, 0, width - hudW());
                config.hudY = clamp(ny, 0, height - hudH());
            }
            case EVENTS -> {
                config.eventsX = clamp(nx, 0, width - eventsW());
                config.eventsY = clamp(ny, 0, height - eventsH());
            }
            case PROFIT -> {
                config.profitX = clamp(nx, 0, width - profitW());
                config.profitY = clamp(ny, 0, height - profitH());
            }
            case CHAT -> {
                config.chatToastX = clamp(nx, 0, width - chatW());
                config.chatToastY = clamp(ny, 0, height - chatH());
            }
            case NONE -> {
            }
        }
    }

    // ------------------------------------------------------------- dibujado

    private static void outline(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, int color) {
        g.fill(x1, y1, x2, y1 + 1, color);
        g.fill(x1, y2 - 1, x2, y2, color);
        g.fill(x1, y1, x1 + 1, y2, color);
        g.fill(x2 - 1, y1, x2, y2, color);
    }

    private boolean hovered(Target t, int mouseX, int mouseY) {
        return dragging == t || (dragging == Target.NONE && targetAt(mouseX, mouseY) == t);
    }

    /** Marco de un elemento de texto: azul al pasar el ratón, rojizo si está desactivado. */
    private void frame(GuiGraphicsExtractor g, int x, int y, int w, int h, boolean over, boolean enabled) {
        g.fill(x - 2, y - 2, x + w + 2, y + h + 2, 0x60000000);
        outline(g, x - 2, y - 2, x + w + 2, y + h + 2, over ? ACCENT : (enabled ? BORDER : BORDER_OFF));
    }

    private void centered(GuiGraphicsExtractor g, String text, int y, int color) {
        g.text(font, text, (width - font.width(text)) / 2, y, color, true);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        mouseXNow = mouseX;
        mouseYNow = mouseY;
        if (dragging != Target.NONE) applyDrag(mouseX, mouseY);

        super.extractRenderState(g, mouseX, mouseY, delta); // fondo + botones

        // Referencia: la barra donde se escribe en el chat
        g.fill(2, height - 14, width - 2, height - 2, 0x80000000);
        g.text(font, "(barra del chat)", 6, height - 12, GRAY, false);

        // Botón del chat
        int bx = buttonX();
        int by = buttonY();
        int size = config.buttonSize;
        boolean overButton = hovered(Target.BUTTON, mouseX, mouseY);
        g.fill(bx, by, bx + size, by + size, CARD);
        outline(g, bx - 1, by - 1, bx + size + 1, by + size + 1, overButton ? ACCENT : BORDER);
        g.text(font, "BL", bx + (size - font.width("BL")) / 2, by + (size - 8) / 2, WHITE, false);

        // Alertas (vista previa)
        frame(g, config.hudX, config.hudY, hudW(), hudH(),
                hovered(Target.HUD, mouseX, mouseY), config.hudEnabled);
        BlacklistHud.renderPreview(g, config.hudX, config.hudY, config.hudScale);

        // Tracker de eventos (vista previa)
        frame(g, config.eventsX, config.eventsY, eventsW(), eventsH(),
                hovered(Target.EVENTS, mouseX, mouseY), config.eventsEnabled);
        EventsHud.renderPreview(g, config.eventsX, config.eventsY, config.eventsScale);

        // Profit de cofres (vista previa)
        frame(g, config.profitX, config.profitY, profitW(), profitH(),
                hovered(Target.PROFIT, mouseX, mouseY), config.profitEnabled);
        ProfitHud.renderPreview(g, config.profitX, config.profitY, config.profitScale);

        // Aviso del chat (vista previa)
        frame(g, config.chatToastX, config.chatToastY, chatW(), chatH(),
                hovered(Target.CHAT, mouseX, mouseY), config.chatToastEnabled);
        ChatToastHud.renderPreview(g, config.chatToastX, config.chatToastY, config.chatToastScale);

        // Instrucciones y valores actuales
        centered(g, "Arrastra el botón del chat y los elementos para moverlos", height / 2 - 22, WHITE);
        centered(g, "Rueda del ratón sobre un elemento: cambiar su tamaño", height / 2 - 10, WHITE);
        centered(g, "Marco rojo = desactivado (se activa en la pestaña QoL / Ajustes)", height / 2 + 2, GRAY);
        centered(g, "Se guarda solo  ·  Esc o Listo para salir", height / 2 + 14, GRAY);

        centered(g, "Botón: " + config.buttonFromRight + " px del borde derecho, "
                + config.buttonFromBottom + " px del inferior  ·  tamaño " + config.buttonSize,
                height / 2 + 28, GRAY);
        centered(g, "Alertas: " + config.hudX + ", " + config.hudY + "  ·  escala " + config.hudScale + "x",
                height / 2 + 40, GRAY);
        centered(g, "Eventos: " + config.eventsX + ", " + config.eventsY + "  ·  escala " + config.eventsScale + "x",
                height / 2 + 52, GRAY);
        centered(g, "Profit: " + config.profitX + ", " + config.profitY + "  ·  escala " + config.profitScale + "x",
                height / 2 + 64, GRAY);
        centered(g, "Aviso del chat: " + config.chatToastX + ", " + config.chatToastY + "  ·  escala "
                + config.chatToastScale + "x", height / 2 + 76, GRAY);
    }
}
