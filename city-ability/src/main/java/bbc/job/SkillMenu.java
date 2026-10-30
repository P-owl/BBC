package bbc.job;

import bbc.core.ButtonMenu;
import bbc.core.PlayerData;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Items;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.List;

/**
 * 기술 장착 창. 윗줄 = 기술 풀, 아랫줄 = 1~5 칸.
 * 기술을 누르면 비어 있는 칸(없으면 마지막 칸)에 장착, 아랫줄 칸을 누르면 비운다.
 */
public class SkillMenu extends ButtonMenu {
    private static final int SLOT_ROW = 18;

    public static void open(ServerPlayerEntity p) {
        if (JobSkills.of(Jobs.entry(p)) == null) { JobSkills.bar(p, "전투 기술이 있는 직업이 아닙니다", Formatting.GRAY); return; }
        p.openHandledScreen(new SimpleNamedScreenHandlerFactory((id, inv, x) -> new SkillMenu(id, inv, p), Text.literal("기술 장착 (1~5)")));
    }

    private SkillMenu(int syncId, PlayerInventory inv, ServerPlayerEntity player) {
        super(syncId, inv, player, 3);
        draw();
    }

    private void draw() {
        PlayerData.Entry e = Jobs.entry(player);
        JobSkills.Set set = JobSkills.of(e);
        JobSkills.Skill[] skills = set.skills(player, e);
        int[] eq = JobSkills.equipped(e);
        int rank = Jobs.rank(e);
        for (int i = 0; i < 18; i++) {
            if (i >= skills.length || skills[i] == null) { button(i, icon(Items.GRAY_STAINED_GLASS_PANE, " ", Formatting.GRAY)); continue; }
            JobSkills.Skill s = skills[i];
            boolean on = contains(eq, i), locked = s.rank() > rank;
            List<String> lore = JobSkills.describe(s, set);
            lore.add(locked ? "잠김" : on ? "장착됨" : "▶ 클릭해서 장착");
            button(i, icon(locked ? Items.BARRIER : on ? Items.LIME_DYE : Items.PAPER, s.name(), locked ? Formatting.DARK_GRAY : on ? Formatting.GREEN : Formatting.WHITE, lore.toArray(new String[0])));
        }
        for (int k = 0; k < JobSkills.SLOTS; k++) {
            int idx = eq[k];
            boolean has = idx >= 0 && idx < skills.length && skills[idx] != null;
            button(SLOT_ROW + 2 + k, icon(has ? Items.ENCHANTED_BOOK : Items.BOOK, JobSkills.KEYS[k] + (has ? " — " + skills[idx].name() : " — 비어 있음"), Formatting.GOLD, has ? "▶ 클릭해서 빼기" : ""));
        }
        refresh();
    }

    private static boolean contains(int[] a, int v) { for (int x : a) if (x == v) return true; return false; }

    @Override
    protected boolean onPick(int slot) {
        PlayerData.Entry e = Jobs.entry(player);
        JobSkills.Skill[] skills = JobSkills.of(e).skills(player, e);
        int[] eq = JobSkills.equipped(e);
        if (slot >= SLOT_ROW + 2 && slot < SLOT_ROW + 2 + JobSkills.SLOTS) {
            eq[slot - SLOT_ROW - 2] = -1;
        } else if (slot < 18 && slot < skills.length && skills[slot] != null && skills[slot].rank() <= Jobs.rank(e) && !contains(eq, slot)) {
            int free = -1;
            for (int k = 0; k < JobSkills.SLOTS; k++) if (eq[k] < 0) { free = k; break; }
            eq[free < 0 ? JobSkills.SLOTS - 1 : free] = slot;
        } else {
            return false;
        }
        JobSkills.setEquipped(e, eq);
        Jobs.dirty(player);
        draw();
        return false;
    }
}
