package com.tuusuario.blacklist.dungeonrun;
import java.util.*;
public class MapSelfTest {
  static int fails=0;
  static void eq(String w,Object got,Object want){ boolean ok=Objects.equals(got,want); if(!ok)fails++; System.out.println((ok?"OK   ":"FAIL ")+w+" got="+got+" want="+want);}
  static void fill(byte[] m,int x,int y,int w,int h,int c){ for(int j=y;j<y+h;j++)for(int i=x;i<x+w;i++) m[j*128+i]=(byte)c; }
  public static void main(String[] a){
    int size=16, step=20, sx=5, sy=5;
    byte[] m=new byte[16384];
    // cell painter
    java.util.function.BiConsumer<int[],Integer> cell=(rc,col)->fill(m,sx+rc[0]*step,sy+rc[1]*step,size,size,col);
    cell.accept(new int[]{0,0},30);            // entrance
    cell.accept(new int[]{1,0},63); cell.accept(new int[]{2,0},63); fill(m,sx+1*step+size,sy,4,size,63); // 1x2
    cell.accept(new int[]{0,1},66);            // puzzle
    // door between entrance and (1,0): only center pixels (brown), must NOT merge
    fill(m,sx+size,sy+5,4,6,63);
    cell.accept(new int[]{4,0},74);            // yellow
    int[][] L={{3,2},{3,3},{4,3}}; for(int[] c:L) cell.accept(c,63);
    fill(m,sx+3*step,sy+2*step+size,size,4,63); fill(m,sx+3*step+size,sy+3*step,4,size,63);
    for(int x=1;x<=2;x++)for(int z=4;z<=5;z++) cell.accept(new int[]{x,z},63); // 2x2
    fill(m,sx+1*step+size,sy+4*step,4,2*step-4,63); fill(m,sx+1*step,sy+4*step+size,2*step-4,4,63);
    cell.accept(new int[]{5,5},85);            // unknown
    // check mark in center of puzzle must not matter
    fill(m,sx+0*step+6,sy+1*step+6,4,4,34);
    DungeonMap d=DungeonMap.parse(m);
    eq("parsed",d!=null,true);
    eq("roomPx",d.roomPx,16); eq("start",d.startX+","+d.startY,"5,5"); eq("cols",d.cols,6);
    eq("rooms",d.rooms.size(),7);
    eq("entrance",d.roomAt(0,0).type,"entrance");
    eq("1x2",d.roomAt(1,0).shape,"1x2"); eq("1x2 same",d.roomAt(1,0)==d.roomAt(2,0),true);
    eq("door no merge",d.roomAt(0,0)==d.roomAt(1,0),false);
    eq("puzzle",d.roomAt(0,1).type,"puzzle");
    eq("yellow",d.roomAt(4,0).type,"yellow");
    eq("L",d.roomAt(3,2).shape,"L"); eq("L cells",d.roomAt(3,2).cells.size(),3);
    eq("2x2",d.roomAt(1,4).shape,"2x2");
    eq("unknown",d.roomAt(5,5).type,"unknown");
    eq("empty",d.roomAt(5,0),null);
    eq("known",d.knownCells(),1+2+1+1+3+4);
    eq("doors", d.doors.size(), 1);
    eq("door pos", d.doors.get(0).x+","+d.doors.get(0).z+","+d.doors.get(0).dir+","+d.doors.get(0).kind, "0,0,0,0");
    eq("no entrance",DungeonMap.parse(new byte[16384]),null);

    // resolver
    RoomIndex.Entry e1=new RoomIndex.Entry(); e1.name="Zoo"; e1.type="normal"; e1.shape="1x2"; e1.secrets=4; e1.cores=new int[]{111};
    RoomIndex.Entry e2=new RoomIndex.Entry(); e2.name="Foo"; e2.type="normal"; e2.shape="L"; e2.cores=new int[]{222};
    RoomIndex.Entry e3=new RoomIndex.Entry(); e3.name="Bar"; e3.type="normal"; e3.shape="L"; e3.cores=new int[]{333};
    RoomIndex.Entry e4=new RoomIndex.Entry(); e4.name="Entrance"; e4.type="entrance"; e4.shape="1x1"; e4.cores=new int[]{1};
    RoomIndex idx=RoomIndex.of(List.of(e1,e2,e3,e4));
    Map<String,Integer> cores=new HashMap<>(); cores.put("2,0",111);
    Map<String,Map<String,Double>> t=new LinkedHashMap<>();
    t.put("1,0",new LinkedHashMap<>(Map.of("A",5.0))); t.put("2,0",new LinkedHashMap<>(Map.of("A",3.0,"B",2.0))); t.put("0,0",new LinkedHashMap<>(Map.of("A",1.0)));
    t.put("3,3",new LinkedHashMap<>(Map.of("A",1.0))); t.put("0,5",new LinkedHashMap<>(Map.of("A",9.0))); // 0,5 fuera del mapa
    List<RunData.RoomEntry> rs=new MapRoomResolver(d,idx,cores).resolve(t);
    Map<String,RunData.RoomEntry> by=new HashMap<>(); for(RunData.RoomEntry r:rs) by.put(r.name,r);
    eq("rooms out",rs.size(),8);
    eq("core name",by.containsKey("Zoo"),true);
    eq("Zoo secs A",by.get("Zoo").seconds.get("A"),8.0); eq("Zoo B",by.get("Zoo").seconds.get("B"),2.0); eq("Zoo secrets",by.get("Zoo").secrets,4);
    eq("Entrance by type",by.get("Entrance").identified,true);
    eq("L ambiguous",by.containsKey("Sala ? (L)"),true);
    eq("outside kept",by.containsKey("Celda 0,5"),true);
    eq("unexplored",by.containsKey("Sala sin explorar"),true);
    eq("null map fallback",new MapRoomResolver(null,idx,cores).resolve(t).size(),5);

    // party
    eq("tab1",PartyParser.memberName("[352] Steve_1 (Mage 38)"),"Steve_1");
    eq("tab2",PartyParser.memberName("[300] \u00a7bAlex \u2672 (Healer 12)"),"Alex");
    eq("tab dead",PartyParser.memberName("[10] Bob (DEAD)"),"Bob");
    eq("tab nonparty",PartyParser.memberName("Team Score: 120 (S)"),null);
    eq("tab empty",PartyParser.memberName("[?] (EMPTY)"),null);
    Set<String> party=Set.of("Steve_1");
    UUID v4=UUID.fromString("123e4567-e89b-42d3-a456-426614174000"), v2=UUID.fromString("123e4567-e89b-22d3-a456-426614174000");
    eq("real",PartyParser.isRealPlayer(v4,"Steve_1",party,false),true);
    eq("npc uuid",PartyParser.isRealPlayer(v2,"Steve_1",party,false),false);
    eq("mob name",PartyParser.isRealPlayer(v4,"Shadow Assassin",party,false),false);
    eq("not in party",PartyParser.isRealPlayer(v4,"Randomx",party,false),false);
    eq("empty party fallback",PartyParser.isRealPlayer(v4,"Randomx",Set.of(),false),true);
    eq("local",PartyParser.isRealPlayer(v2,"x y",party,true),true);

    // legacy ids
    eq("stone bricks",LegacyBlocks.idOf("mossy_stone_bricks",false,false),98);
    eq("planks",LegacyBlocks.idOf("dark_oak_planks",false,false),5);
    eq("wool",LegacyBlocks.idOf("red_wool",false,false),35);
    eq("slab",LegacyBlocks.idOf("stone_slab",false,true)+0,43);
    eq("wood slab",LegacyBlocks.idOf("oak_slab",false,false),126);
    eq("acacia log",LegacyBlocks.idOf("acacia_log",false,false),162);
    eq("lit ore",LegacyBlocks.idOf("redstone_ore",true,false),74);
    eq("unknown",LegacyBlocks.idOf("weird_block",false,false),-1);
    System.out.println(fails==0?"TODO OK":fails+" FALLAS"); System.exit(fails==0?0:1);
  }
}
