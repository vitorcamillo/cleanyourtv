package com.gathin.tvhome;

import org.junit.Test;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

public class MoonlightCatalogTest {
    private static final String UUID = "86371409-d0b3-4bab-b750-319550ec58b4";
    private MoonlightCatalog.Game game(String n, String id, String image) { return new MoonlightCatalog.Game(n,id,image); }
    @Test public void allowsNamesForMoonlightCachedLookupAndCopiesInput() {
        List<MoonlightCatalog.Game> games = new ArrayList<>();
        games.add(game("Mario Kart 8 Deluxe",null,"mario-kart.png"));
        MoonlightCatalog c = new MoonlightCatalog("Wolf",UUID.toUpperCase(),games);
        games.clear(); assertEquals(1,c.games.size()); assertEquals(UUID,c.hostUuid); assertNull(c.games.get(0).appId);
        assertThrows(UnsupportedOperationException.class,()->c.games.clear());
    }
    @Test public void rejectsMalformedHostAndTitles() {
        for(String bad:new String[]{null,"","1-1-1-1-1",UUID+"x","../host"}) {
            assertThrows(IllegalArgumentException.class,()->new MoonlightCatalog("Wolf",bad,Collections.singletonList(game("Game",null,null))));
        }
        for(String bad:new String[]{null,""," "," Game","Game ","x\ny","x\u0000y",String.join("",Collections.nCopies(121,"x"))}) {
            assertThrows(IllegalArgumentException.class,()->game(bad,null,null));
        }
        assertEquals(120,game(String.join("",Collections.nCopies(120,"x")),null,null).name.length());
    }
    @Test public void idsHaveStrictIntegerBounds() {
        for(String bad:new String[]{"0","-1","01","1.0","2147483648","999999999999","1;cmd"," 1"}) {
            assertThrows(IllegalArgumentException.class,()->game("Game",bad,null));
        }
        assertEquals("2147483647",game("Game","2147483647",null).appId);
        assertEquals("1",game("Game","1",null).appId);
    }
    @Test public void blocksPathTraversalUrlsAndExecutableCovers() {
        for(String bad:new String[]{"../x.png","/x.png","x/y.png","x\\y.png","https://x.png","x.svg","x.png.exe",".png","x.PNG"}) {
            assertThrows(IllegalArgumentException.class,()->game("Game",null,bad));
        }
        assertEquals("game-1_cover.jpeg",game("Game",null,"game-1_cover.jpeg").image);
    }
    @Test public void enforcesCountAndUniqueness() {
        assertThrows(IllegalArgumentException.class,()->new MoonlightCatalog("Wolf",UUID,null));
        assertThrows(IllegalArgumentException.class,()->new MoonlightCatalog("Wolf",UUID,Collections.emptyList()));
        List<MoonlightCatalog.Game> list=new ArrayList<>();
        for(int i=0;i<64;i++)list.add(game("Game "+i,""+(i+1),null));
        assertEquals(64,new MoonlightCatalog("Wolf",UUID,list).games.size());
        list.add(game("overflow",null,null));
        assertThrows(IllegalArgumentException.class,()->new MoonlightCatalog("Wolf",UUID,list));
        assertThrows(IllegalArgumentException.class,()->new MoonlightCatalog("Wolf",UUID,java.util.Arrays.asList(game("A","1",null),game("B","1",null))));
        assertThrows(IllegalArgumentException.class,()->new MoonlightCatalog("Wolf",UUID,java.util.Arrays.asList(game("A",null,null),game("A",null,null))));
        assertThrows(IllegalArgumentException.class,()->new MoonlightCatalog("Wolf",UUID,java.util.Arrays.asList((MoonlightCatalog.Game)null)));
    }
    @Test public void refusesSymlinksOutsideCatalog() throws Exception {
        File directory=Files.createTempDirectory("moonlight-test").toFile();
        File outside=Files.createTempFile("cover", ".png").toFile();
        try {
            Files.createSymbolicLink(new File(directory,"escape.png").toPath(),outside.toPath());
            assertThrows(java.io.IOException.class,()->MoonlightCatalog.localFile(directory,"escape.png"));
            assertThrows(java.io.IOException.class,()->MoonlightCatalog.localFile(directory,"../escape.png"));
            assertEquals(directory,MoonlightCatalog.localFile(directory,"valid.png").getParentFile());
            assertThrows(java.io.IOException.class,()->MoonlightCatalog.localFile(directory,"script.svg"));
            assertThrows(java.io.IOException.class,()->MoonlightCatalog.localFile(directory,null));
            assertEquals("catalog.json",MoonlightCatalog.localFile(directory,"catalog.json").getName());
        } finally { Files.deleteIfExists(new File(directory,"escape.png").toPath()); outside.delete(); directory.delete(); }
    }
}
