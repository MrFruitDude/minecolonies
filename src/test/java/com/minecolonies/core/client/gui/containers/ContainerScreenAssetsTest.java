package com.minecolonies.core.client.gui.containers;

import org.junit.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Rack bug ("opening the rack, the UI was broken / missing"): the 26.x port left the legacy {@code blit(texture, x, y, u, v, w, h, texW, texH)} calls in the
 * container screens. In 26.x the only pipeline-less {@code GuiGraphicsExtractor.blit} is {@code (Identifier, x0, x1, y0, y1, float u0, u1, v0, v1)}, so the old call
 * still compiled (ints widen to floats) but drew a degenerate quad: no panel, no slot frames, only floating items and labels. These checks need no Minecraft
 * classes loaded: they read the compiled screens' constant pools, the sources and the texture files.
 */
public class ContainerScreenAssetsTest
{
    private static final String PACKAGE_DIR = "com/minecolonies/core/client/gui/containers/";
    private static final String TEXTURE_DIR = "src/main/resources/assets/minecolonies/textures/gui/";
    /** The pipeline-less 9-argument blit: (Identifier, int x0, x1, y0, y1, float u0, u1, v0, v1). Legacy 9-number calls bind to it by accident. */
    private static final String ATLAS_BLIT_DESCRIPTOR = "(Lnet/minecraft/resources/Identifier;IIIIFFFF)V";
    private static final int PANEL_TEXTURE_SIZE = 350;

    /** The background textures the AbstractContainerScreen subclasses draw with a 350x350 map (WindowRack, WindowGrave, WindowBuildingInventory, WindowCitizenInventory). */
    private static final String[] PANEL_TEXTURES = {"generic_108", "gui81", "gui90", "gui99", "gui108", "citizen_container"};
    /** The 256x256 maps of the crafting screens (drawn through BlockUI's UiRenderMacros). */
    private static final String[] CRAFTING_TEXTURES = {"crafting2x2", "crafting3x3", "furnace"};

    /** The screen classes: every Window*.java next to WindowRack (the test classpath's package dir of the same name is the tests', so list the sources). */
    private static List<String> screenClasses() throws IOException
    {
        final List<String> names = new ArrayList<>();
        try (var files = Files.list(Paths.get("src/main/java/" + PACKAGE_DIR)))
        {
            files.map(p -> p.getFileName().toString())
              .filter(n -> n.startsWith("Window") && n.endsWith(".java"))
              .forEach(n -> names.add(n.substring(0, n.length() - ".java".length()) + ".class"));
        }
        assertTrue("no Window* sources found in " + PACKAGE_DIR, names.size() >= 8);
        return names;
    }

    @Test
    public void noContainerScreenUsesTheAtlasBlitByAccident() throws Exception
    {
        final List<String> offenders = new ArrayList<>();
        for (final String name : screenClasses())
        {
            try (InputStream in = ContainerScreenAssetsTest.class.getClassLoader().getResourceAsStream(PACKAGE_DIR + name))
            {
                assertNotNull("compiled " + name + " not on the test classpath", in);
                final String pool = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
                if (pool.contains(ATLAS_BLIT_DESCRIPTOR))
                {
                    offenders.add(name);
                }
            }
        }
        assertTrue("these container screens call GuiGraphicsExtractor.blit(Identifier, int x4, float x4) with legacy blit(tex, x, y, u, v, w, h, texW, texH) "
                     + "arguments and draw nothing; pass RenderPipelines.GUI_TEXTURED first: " + offenders, offenders.isEmpty());
    }

    @Test
    public void everyRackScreenTextureExistsAndHasTheMapSizeTheScreenAssumes() throws IOException
    {
        for (final String texture : PANEL_TEXTURES)
        {
            final BufferedImage image = ImageIO.read(new File(TEXTURE_DIR + texture + ".png"));
            assertNotNull("missing or unreadable texture " + texture, image);
            assertEquals(texture + " width (the screens pass " + PANEL_TEXTURE_SIZE + " as the texture map size)", PANEL_TEXTURE_SIZE, image.getWidth());
            assertEquals(texture + " height", PANEL_TEXTURE_SIZE, image.getHeight());
        }
        for (final String texture : CRAFTING_TEXTURES)
        {
            final BufferedImage image = ImageIO.read(new File(TEXTURE_DIR + texture + ".png"));
            assertNotNull("missing or unreadable texture " + texture, image);
            assertEquals(texture + " width", 256, image.getWidth());
            assertEquals(texture + " height", 256, image.getHeight());
        }
    }

    @Test
    public void everyTextureNamedInTheScreenSourcesExists() throws IOException
    {
        final Pattern literal = Pattern.compile("\"textures/gui/([a-z0-9_]+)\\.png\"");
        final Path dir = Paths.get("src/main/java/" + PACKAGE_DIR);
        int found = 0;
        try (var files = Files.list(dir))
        {
            for (final Path source : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator)
            {
                final Matcher m = literal.matcher(Files.readString(source));
                while (m.find())
                {
                    found++;
                    final String texture = m.group(1);
                    // The brewing stand screen draws the vanilla texture; everything under minecolonies:textures/gui must be shipped.
                    if (!source.getFileName().toString().equals("WindowBrewingstandCrafting.java"))
                    {
                        assertTrue(source.getFileName() + " names textures/gui/" + texture + ".png which is not shipped", new File(TEXTURE_DIR + texture + ".png").isFile());
                    }
                }
            }
        }
        assertTrue("expected the screens to name textures", found >= 5);
        // WindowRack and WindowGrave build "textures/gui/gui%s.png" from rows * 9: 9..12 rows give 81, 90, 99, 108.
        for (final int slots : new int[] {81, 90, 99, 108})
        {
            assertTrue("textures/gui/gui" + slots + ".png (rack/grave with " + slots / 9 + " rows)", new File(TEXTURE_DIR + "gui" + slots + ".png").isFile());
        }
    }

    @Test
    public void everyContainerMenuTypeHasAScreenRegistered() throws IOException
    {
        final String initializer = Files.readString(Paths.get("src/main/java/com/minecolonies/apiimp/initializer/ModContainerInitializers.java"));
        final Pattern menu = Pattern.compile("ModContainers\\.(\\w+) = CONTAINERS\\.register");
        final Matcher m = menu.matcher(initializer);
        final List<String> missing = new ArrayList<>();
        int menus = 0;
        while (m.find())
        {
            menus++;
            if (!initializer.contains("event.register(ModContainers." + m.group(1) + ".get(),"))
            {
                missing.add(m.group(1));
            }
        }
        assertTrue("expected at least the rack, grave, building, citizen and crafting menus", menus >= 7);
        if (!missing.isEmpty())
        {
            fail("menu types registered without a screen in RegisterMenuScreensEvent: " + missing);
        }
        assertTrue("the rack menu must have WindowRack", initializer.contains("event.register(ModContainers.rackInv.get(), WindowRack::new);"));
    }
}
