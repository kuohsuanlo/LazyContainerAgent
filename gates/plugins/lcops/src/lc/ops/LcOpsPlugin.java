package lc.ops;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.util.*;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.DoubleChest;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.CrafterCraftEvent;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.block.BlockFace;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.BlockInventoryHolder;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 測試輔助外掛 v2:
 *  (1) 伺服器端操作痕跡 trace.log(bot 宣稱不算數,這裡記的才算):
 *      OPEN(不讀內容——讀了會搶在玩家之前物化、汙染歸因)/ CLOSE(此時記非空格數)/ CLICK / BREAK / CMD / CHUNK load,unload / LCOPS / CMDRESULT
 *  (2) go-api → ops.txt 的 Bukkit API 操作(getState/update、setContents、clear)
 *  (3) go-cmds → cmds.txt 的指令,以可截取回饋的 CommandSender 執行,回饋文字寫進 trace(CMDRESULT),成功與否可驗
 */
public final class LcOpsPlugin extends JavaPlugin implements Listener {
    private PrintWriter trace;
    private boolean ranApi = false, ranCmds = false;
    /** 要記錄 chunk 事件的 region:regions.txt 每行「<維度> <rx> <rz>」(由 gate 腳本依素材寫入)。 */
    private final Set<String> watchRegions = new HashSet<>();
    /**
     * 機械碰過的容器位置 → [第一次, 最後一次](epoch ms)。
     * 來源:漏斗/投擲器搬運(InventoryMoveItemEvent 的來源與目的方塊)、發射器/投擲器動作(BlockDispenseEvent,
     * 記發射器本身與六鄰——發射器會把界伏盒放到自己面前)。判定用這份當「可解釋」的證據,
     * 取代原本「六鄰有漏斗」的幾何猜測(猜太窄:農場的發射器補放界伏盒、投擲器塞東西都抓不到)。
     * 事件量極大(s3 有兩萬多個漏斗),所以只留 HashMap、每 30 秒與關閉時落檔,不逐筆寫 trace。
     */
    private final Map<String, long[]> machinery = new HashMap<>();
    private final Map<String, String> machineryKind = new HashMap<>();

    @Override public void onEnable() {
        getDataFolder().mkdirs();
        try { trace = new PrintWriter(new FileWriter(new File(getDataFolder(), "trace.log"), true), true); }
        catch (Exception e) { getLogger().severe("trace.log 開不了: " + e); }
        try {
            File rf = new File(getDataFolder(), "regions.txt");
            if (rf.exists()) for (String line : Files.readAllLines(rf.toPath())) {
                String[] p = line.trim().split("\\s+");
                if (p.length >= 3) watchRegions.add(p[0] + " " + p[1] + " " + p[2]);
            }
        } catch (Exception e) { getLogger().warning("regions.txt: " + e); }
        getLogger().info("LCOPS watching regions " + watchRegions);
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskTimer(this, this::poll, 100L, 60L);
        Bukkit.getScheduler().runTaskTimer(this, this::flushMachinery, 600L, 600L);
    }

    @Override public void onDisable() { flushMachinery(); }

    private void touch(World w, int x, int y, int z, String kind) {
        String k = dimOf(w) + "\t" + x + "," + y + "," + z;
        long now = System.currentTimeMillis();
        long[] t = machinery.get(k);
        if (t == null) machinery.put(k, new long[]{now, now}); else t[1] = now;
        String old = machineryKind.get(k);
        if (old == null) machineryKind.put(k, kind); else if (!old.contains(kind)) machineryKind.put(k, old + kind);
    }
    private void touchHolder(InventoryHolder h) {
        if (h instanceof DoubleChest dc) { touchHolder(dc.getLeftSide()); touchHolder(dc.getRightSide()); }
        else if (h instanceof BlockInventoryHolder bh) { Block b = bh.getBlock(); touch(b.getWorld(), b.getX(), b.getY(), b.getZ(), "M"); }
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void onMove(InventoryMoveItemEvent e) {
        if (e.isCancelled()) return;
        touchHolder(e.getSource().getHolder()); touchHolder(e.getDestination().getHolder());
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDispense(BlockDispenseEvent e) {
        if (e.isCancelled()) return;
        Block b = e.getBlock(); touch(b.getWorld(), b.getX(), b.getY(), b.getZ(), "D");
        for (BlockFace f : new BlockFace[]{BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
            Block n = b.getRelative(f); touch(n.getWorld(), n.getX(), n.getY(), n.getZ(), "D");
        }
    }
    /** 合成器合成:物品數量不守恆的唯一合法來源。記結果與消耗量,裁判用來算「預期的總數變化」。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onCraft(CrafterCraftEvent e) {
        if (e.isCancelled()) return;
        Block b = e.getBlock();
        int consumed = 0;
        Recipe r = e.getRecipe();
        if (r instanceof ShapedRecipe sr) { for (RecipeChoice c : sr.getChoiceMap().values()) if (c != null) consumed++; }
        else if (r instanceof ShapelessRecipe sl) consumed = sl.getChoiceList().size();
        ItemStack res = e.getResult();
        touch(b.getWorld(), b.getX(), b.getY(), b.getZ(), "C");
        t("CRAFT", "crafter", dimOf(b.getWorld()), b.getX(), b.getY(), b.getZ(),
          "result=" + res.getType().getKey() + " amount=" + res.getAmount() + " consumed=" + consumed);
    }
    private synchronized void flushMachinery() {
        try (PrintWriter w = new PrintWriter(new FileWriter(new File(getDataFolder(), "machinery.tsv"), false))) {
            for (Map.Entry<String, long[]> en : machinery.entrySet())
                w.println(en.getKey() + "\t" + en.getValue()[0] + "\t" + en.getValue()[1] + "\t" + machineryKind.getOrDefault(en.getKey(), "M"));
        } catch (Exception ex) { getLogger().warning("machinery.tsv: " + ex); }
    }
    /**
     * 把素材 region 內每個已載入容器的物化狀態倒成 pending.tsv(dim \t x,y,z \t pending)。
     * 讀的是活體 BlockEntity 上被 splice 進去的 {@code lazycontainer$pending}(public volatile),
     * 這是「這個容器有沒有被解碼過」的唯一權威證據——「未被碰卻結構變了」的容器必須是被物化過的
     * (物化後由原版重編碼,寫法可能變、內容不變),否則就是直寫沒保真。
     */
    private void dumpPending() {
        // 註:區域執行緒核心上,跨區讀活體 BlockEntity 會被拒。這裡讀的是 chunk 的 blockEntities map
        // 與一個 volatile 欄位,不改任何狀態;若核心仍拒絕,下面的 try/catch 會讓該 chunk 略過而不是整個炸掉。
        int n = 0, pend = 0;
        try (PrintWriter w = new PrintWriter(new FileWriter(new File(getDataFolder(), "pending.tsv"), false))) {
            for (World world : Bukkit.getWorlds()) {
                net.minecraft.server.level.ServerLevel lvl = ((org.bukkit.craftbukkit.CraftWorld) world).getHandle();
                for (Chunk c : world.getLoadedChunks()) {
                    if (!watchRegions.contains(dimOf(world) + " " + (c.getX() >> 5) + " " + (c.getZ() >> 5))) continue;
                    // ⚠ 不能用 Bukkit 的 c.getTileEntities():它為每個容器建 BlockState 快照,
                    // 而快照走的是 saveWithFullMetadata → trySaveRaw 的「非存檔視窗」路徑,
                    // 會把 rawSave 灌上 11 萬次而 rawPassthrough 不動,直接把 G7 的比例閘門打紅(實際踩過)。
                    // 直接讀 NMS chunk 的 blockEntities map,不建任何快照。
                    Map<net.minecraft.core.BlockPos, net.minecraft.world.level.block.entity.BlockEntity> bes;
                    try { bes = lvl.getChunk(c.getX(), c.getZ()).getBlockEntities(); }
                    catch (Throwable th) { continue; }      // 區域執行緒核心可能拒絕跨區讀,略過該 chunk
                    for (Map.Entry<net.minecraft.core.BlockPos, net.minecraft.world.level.block.entity.BlockEntity> en : bes.entrySet()) {
                        net.minecraft.world.level.block.entity.BlockEntity be = en.getValue();
                        if (!(be instanceof net.minecraft.world.level.block.entity.BaseContainerBlockEntity)) continue;
                        Boolean p = null;
                        try { p = (Boolean) net.minecraft.world.level.block.entity.BaseContainerBlockEntity.class.getField("lazycontainer$pending").get(be); }
                        catch (Throwable ignored) {}
                        if (p == null) continue;
                        n++; if (p) pend++;
                        net.minecraft.core.BlockPos bp = en.getKey();
                        w.println(dimOf(world) + "\t" + bp.getX() + "," + bp.getY() + "," + bp.getZ() + "\t" + p);
                    }
                }
            }
        } catch (Exception ex) { getLogger().warning("pending.tsv: " + ex); }
        getLogger().info("LCDUMP pending containers=" + n + " pending=" + pend);
    }

    private void t(String kind, String who, String dim, int x, int y, int z, String extra) {
        if (trace != null) trace.println(System.currentTimeMillis() + "\t" + kind + "\t" + who + "\t" + dim + "\t" + x + "," + y + "," + z + "\t" + extra.replace('\n', ' ').replace('\t', ' '));
    }
    private static String dimOf(World w) { return w.getKey().getKey(); }
    private void holderTrace(String kind, String who, InventoryHolder h, String extra) {
        if (h instanceof DoubleChest dc) {
            InventoryHolder l = dc.getLeftSide(), r = dc.getRightSide();
            if (l instanceof BlockInventoryHolder bl) { Block b = bl.getBlock(); t(kind, who, dimOf(b.getWorld()), b.getX(), b.getY(), b.getZ(), "double-left " + extra); }
            if (r instanceof BlockInventoryHolder br) { Block b = br.getBlock(); t(kind, who, dimOf(b.getWorld()), b.getX(), b.getY(), b.getZ(), "double-right " + extra); }
        } else if (h instanceof BlockInventoryHolder bh) {
            Block b = bh.getBlock(); t(kind, who, dimOf(b.getWorld()), b.getX(), b.getY(), b.getZ(), b.getType().getKey().getKey() + " " + extra);
        }
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void onOpen(InventoryOpenEvent e) {
        holderTrace("OPEN", e.getPlayer().getName(), e.getInventory().getHolder(), "type=" + e.getInventory().getType() + " size=" + e.getInventory().getSize() + (e.isCancelled() ? " cancelled" : ""));
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent e) {
        holderTrace("CLOSE", e.getPlayer().getName(), e.getInventory().getHolder(), "nonEmpty=" + countNonEmpty(e.getInventory()) + " size=" + e.getInventory().getSize());
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void onClick(InventoryClickEvent e) {
        ItemStack cur = e.getCurrentItem();
        holderTrace("CLICK", e.getWhoClicked().getName(), e.getInventory().getHolder(),
                "raw=" + e.getRawSlot() + " slot=" + e.getSlot() + " action=" + e.getAction() + " item=" + (cur == null ? "-" : cur.getType().getKey().getKey() + "x" + cur.getAmount()) + (e.isCancelled() ? " cancelled" : ""));
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock(); t("BREAK", e.getPlayer().getName(), dimOf(b.getWorld()), b.getX(), b.getY(), b.getZ(), b.getType().getKey().getKey() + (e.isCancelled() ? " cancelled" : ""));
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void onCmd(PlayerCommandPreprocessEvent e) {
        t("CMD", e.getPlayer().getName(), "-", 0, 0, 0, e.getMessage().length() > 200 ? e.getMessage().substring(0, 200) : e.getMessage());
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnload(ChunkUnloadEvent e) { chunkTrace("UNLOAD", e.getChunk()); }
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent e) { chunkTrace(e.isNewChunk() ? "LOADNEW" : "LOAD", e.getChunk()); }
    private void chunkTrace(String kind, Chunk c) {
        // 只記素材 region 的 chunk 事件(整台伺服器的其他 chunk 不入帳,避免把 trace 洗掉)
        String dim = dimOf(c.getWorld());
        int rx = c.getX() >> 5, rz = c.getZ() >> 5;
        if (!watchRegions.contains(dim + " " + rx + " " + rz)) return;
        t("CHUNK", kind, dim, c.getX(), 0, c.getZ(), "region r." + rx + "." + rz);
    }

    private void poll() {
        File goApi = new File(getDataFolder(), "go-api"), goCmds = new File(getDataFolder(), "go-cmds");
        if (!ranApi && goApi.exists()) { ranApi = true; runApi(); }
        if (!ranCmds && goCmds.exists()) { ranCmds = true; runCmds(); }
        File goDump = new File(getDataFolder(), "go-dump");
        if (goDump.exists()) { goDump.delete(); dumpPending(); try { Files.writeString(new File(getDataFolder(), "done-dump").toPath(), "ok"); } catch (Exception ignored) {} }
    }

    private void runApi() {
        StringBuilder out = new StringBuilder(); int ok = 0, fail = 0;
        try {
            for (String line : Files.readAllLines(new File(getDataFolder(), "ops.txt").toPath())) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] p = line.split("\t"); String kind = p[0], dim = p[1];
                int x = Integer.parseInt(p[2]), y = Integer.parseInt(p[3]), z = Integer.parseInt(p[4]);
                try { String r = doOne(kind, dim, x, y, z); t("LCOPS", "plugin", dim, x, y, z, kind + " " + r); out.append("OK\t").append(line).append("\t").append(r).append('\n'); ok++; }
                catch (Throwable th) { t("LCOPS", "plugin", dim, x, y, z, kind + " FAIL " + th); out.append("FAIL\t").append(line).append("\t").append(th).append('\n'); fail++; getLogger().warning("LCOPS FAIL " + line + " : " + th); }
            }
        } catch (Exception e) { out.append("FAIL\tops.txt\t").append(e).append('\n'); fail++; }
        try { Files.writeString(new File(getDataFolder(), "result.txt").toPath(), out.toString()); } catch (Exception ignored) {}
        getLogger().info("LCOPS DONE ok=" + ok + " fail=" + fail);
        try { Files.writeString(new File(getDataFolder(), "done-api").toPath(), ok + " " + fail); } catch (Exception ignored) {}
    }

    /** cmds.txt 每行:tag \t 指令(不含斜線);回饋文字全部寫進 trace 的 CMDRESULT,供 verify 用「成功字樣」逐條核對。 */
    private void runCmds() {
        int n = 0;
        try {
            for (String line : Files.readAllLines(new File(getDataFolder(), "cmds.txt").toPath())) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] p = line.split("\t", 2); String tag = p[0], cmd = p[1];
                // 目標 chunk 先載入(指令對未載入的方塊會回「That position is not loaded」);tag 形如 kind:x,y,z,指令含 in minecraft:<dim>
                try {
                    String[] xyz = tag.substring(tag.indexOf(':') + 1).split(",");
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("in minecraft:([a-z_]+)").matcher(cmd);
                    if (m.find() && xyz.length == 3) world(m.group(1)).getChunkAt(Integer.parseInt(xyz[0]) >> 4, Integer.parseInt(xyz[2]) >> 4).load();
                } catch (Exception ignored) {}
                StringBuilder fb = new StringBuilder();
                Consumer<Component> sink = c -> fb.append(PlainTextComponentSerializer.plainText().serialize(c)).append(" | ");
                CommandSender sender = Bukkit.createCommandSender(sink);
                boolean dispatched;
                // ⚠ 區域執行緒核心(Folia 系)會拒絕「不擁有那個方塊的執行緒」動它:
                // 從 console/排程執行緒直接 dispatch 會全部失敗(實測 42 條全滅、金絲雀 0/9)。
                // 有 RegionScheduler 就把指令丟到擁有那個座標的區域執行緒上跑;沒有就照舊。
                dispatched = dispatchOnOwningRegion(sender, cmd, tag, fb);
                String f = fb.toString(); if (f.length() > 600) f = f.substring(0, 600) + "…";
                t("CMDRESULT", tag, "-", 0, 0, 0, (dispatched ? "dispatched " : "notdispatched ") + "cmd=" + cmd + " feedback=" + f);
                n++;
            }
        } catch (Exception e) { getLogger().severe("cmds.txt: " + e); }
        getLogger().info("LCCMDS DONE n=" + n);
        try { Files.writeString(new File(getDataFolder(), "done-cmds").toPath(), String.valueOf(n)); } catch (Exception ignored) {}
    }

    /**
     * 在「擁有目標方塊的區域執行緒」上執行指令。
     * 原廠 Paper 沒有 RegionScheduler,反射拿不到就直接執行(行為與以前相同)。
     */
    private boolean dispatchOnOwningRegion(CommandSender sender, String cmd, String tag, StringBuilder fb) {
        java.util.concurrent.atomic.AtomicBoolean ok = new java.util.concurrent.atomic.AtomicBoolean(false);
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        Runnable run = () -> {
            try { ok.set(Bukkit.dispatchCommand(sender, cmd)); }
            catch (Throwable th) { fb.append("EXC ").append(th); }
            finally { done.countDown(); }
        };
        try {
            String[] xyz = tag.substring(tag.indexOf(':') + 1).split(",");
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("in minecraft:([a-z_]+)").matcher(cmd);
            Object rs = Bukkit.class.getMethod("getRegionScheduler").invoke(null);
            if (rs != null && m.find() && xyz.length == 3) {
                World w = world(m.group(1));
                int cx = Integer.parseInt(xyz[0]) >> 4, cz = Integer.parseInt(xyz[2]) >> 4;
                rs.getClass().getMethod("execute", org.bukkit.plugin.Plugin.class, World.class, int.class, int.class, Runnable.class)
                  .invoke(rs, this, w, cx, cz, run);
                if (!done.await(10, java.util.concurrent.TimeUnit.SECONDS)) fb.append("TIMEOUT ");
                return ok.get();
            }
        } catch (NoSuchMethodException ignored) {
            // 原廠 Paper:沒有 RegionScheduler,直接跑
        } catch (Throwable th) {
            fb.append("SCHED-EXC ").append(th).append(" ");
        }
        run.run();
        try { done.await(1, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        return ok.get();
    }

    private World world(String dim) {
        for (World w : Bukkit.getWorlds()) if (dimOf(w).equals(dim)) return w;
        throw new IllegalStateException("no world " + dim);
    }

    private String doOne(String kind, String dim, int x, int y, int z) {
        World w = world(dim);
        w.getChunkAt(x >> 4, z >> 4).load();
        Block b = w.getBlockAt(x, y, z);
        BlockState st = b.getState();
        if (!(st instanceof Container c)) throw new IllegalStateException("not a container: " + b.getType());
        switch (kind) {
            case "stateupdate-noop": { int n = countNonEmpty(c.getSnapshotInventory()); if (n == 0) throw new IllegalStateException("empty target"); boolean u = c.update(true, false); return "snapshotNonEmpty=" + n + " update=" + u; }
            case "stateupdate": { Inventory snap = c.getSnapshotInventory(); int n = countNonEmpty(snap); snap.setItem(3, new ItemStack(Material.STONE, 5)); boolean u = c.update(true, false); return "before=" + n + " update=" + u; }
            case "setcontents-noop": { Inventory inv = c.getInventory(); ItemStack[] cur = inv.getContents(); int n = countNonEmpty(inv); if (n == 0) throw new IllegalStateException("empty target"); inv.setContents(cur); return "slots=" + cur.length + " nonEmpty=" + n; }
            case "setcontents": { Inventory inv = c.getInventory(); ItemStack[] cur = inv.getContents(); cur[0] = new ItemStack(Material.DIAMOND, 1); inv.setContents(cur); return "slots=" + cur.length; }
            case "clear": { Inventory inv = c.getInventory(); int n = countNonEmpty(inv); if (n == 0) throw new IllegalStateException("empty target"); inv.clear(); return "cleared nonEmptyBefore=" + n; }
            default: throw new IllegalArgumentException("kind " + kind);
        }
    }

    private static int countNonEmpty(Inventory inv) { int n = 0; for (ItemStack s : inv.getContents()) if (s != null && !s.getType().isAir()) n++; return n; }
}
