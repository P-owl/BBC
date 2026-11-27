package bbc.client;

import bbc.net.CutscenePayload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.Entity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.Map;

/**
 * 컷신 (각성·각성기 연출). 서버가 CutscenePayload 로 대본 이름을 보내면 그 동안 카메라를 대본대로 움직이고
 * 위아래 검은 띠 + 기술 이름을 띄운다. 카메라는 CameraMixin 이 매 프레임 여기서 받아 감.
 *
 * 대본 = 키 목록. 위치는 시전자 기준 (앞 f, 위 u, 오른쪽 r) — 시전자를 따라다니고 방향은 시작할 때 시선으로 고정.
 * look 도 같은 기준이고, lookTarget 이면 대상의 발 + (0, u, 0) 을 바라봄. cut = 앞 키에서 이어지지 않고 뚝 끊어 넘어감.
 * 끝에서 blendOut 틱 동안 원래 카메라로 부드럽게 돌아옴. lock = 그 동안 움직임 입력을 막음
 */
public final class Cutscene {
    private Cutscene() {}

    record Key(int t, double f, double u, double r, double lf, double lu, double lr, boolean cut, boolean lookTarget) {}
    /** 자막 한 토막: at 틱부터 앞 토막 뒤에 이어 붙어 한 글자씩 나옴 */
    record Line(int at, String text) {}
    record Shot(int len, int blendOut, boolean lock, String title, String sub, int titleAt, Line[] lines, Key... keys) {}
    private static final Line[] NO_LINES = {};

    private static Key k(int t, double f, double u, double r, double lf, double lu, double lr) { return new Key(t, f, u, r, lf, lu, lr, false, false); }
    private static Key cut(int t, double f, double u, double r, double lf, double lu, double lr) { return new Key(t, f, u, r, lf, lu, lr, true, false); }
    private static Key kT(int t, double f, double u, double r, double lu) { return new Key(t, f, u, r, 0, lu, 0, false, true); }
    private static Key cutT(int t, double f, double u, double r, double lu) { return new Key(t, f, u, r, 0, lu, 0, true, true); }

    private static final Map<String, Shot> SHOTS = new HashMap<>();
    static {
        // 연금술사 각성 (170틱 ≈ 8.5초, 참고: 다른 게임의 에드워드 연출 + 각성 음성 길이)
        //   먼지 낀 발 → 얼굴까지 천천히 훑어 올라감 → 얼굴 클로즈업(합장 손뼉, 대사) → 앞 위에서 두 팔을 들어 땅을 내리치는 모습 →
        //   바로 위에서 내려다본 거대한 연성진 → 멀리서 사방으로 가시 숲이 솟는 장면 → 가시 숲 속에서 일어나는 모습(낮은 앞) →
        //   한 발 내딛으며 손바닥을 내미는 모습으로 천천히 다가감
        SHOTS.put("al_awaken", new Shot(170, 10, true, "손뼉 연성", "연금술사 · 각성", 118, NO_LINES,
                cut(0, 1.25, 0.15, 0.35, 0, 0.2, 0),
                k(24, 1.4, 0.45, 0.32, 0, 0.5, 0),
                k(34, 1.35, 1.6, 0.15, 0, 1.55, 0),
                k(58, 1.15, 1.62, 0.08, 0, 1.58, 0),
                cut(60, 3.0, 2.6, 1.0, 0, 1.0, 0),
                k(67, 3.3, 2.9, 1.1, 0, 0.8, 0),
                cut(68, 0.6, 7.5, 0.4, 0, 0, 0),
                k(80, 0.8, 8.5, 0.5, 0, 0, 0),
                cut(81, 8.0, 1.2, 3.5, 0, 1.6, 0),
                k(100, 9.0, 1.6, 1.0, 0, 1.2, 0),
                cut(101, 2.4, 0.6, 0.9, 0, 1.0, 0),
                k(118, 2.8, 1.3, 0.5, 0, 1.4, 0),
                k(136, 2.2, 1.45, 0.25, 0, 1.45, 0),
                k(160, 1.6, 1.5, 0.15, 0, 1.45, 0)));
        // 연금술사 각성기 '대지의 심판' (100틱, 대사 두 토막에 맞춤): 아래에서 두 팔을 든 모습을 천천히 돌아봄 → 손뼉 클로즈업 →
        //   높이 올라가 대상 위로 바위 손이 덮치는 장면. 기술 이름 대신 대사 자막 (가슴 앞에 손을 모은 모습부터)
        SHOTS.put("al_ult", new Shot(100, 10, true, null, null, 0,
                new Line[]{new Line(4, "망할 진리와 함께.."), new Line(57, " 네놈들도 날려주마!")},
                cut(0, 2.2, 0.6, 0.9, 0, 1.3, 0),
                k(30, 2.6, 0.8, -0.6, 0, 1.25, 0),
                k(48, 2.0, 1.1, -0.3, 0, 1.2, 0),
                cut(50, 1.4, 1.5, 0.2, 0, 1.3, 0),
                k(59, 1.5, 1.4, 0.3, 0, 1.0, 0),
                cutT(60, -3.2, 6.5, 2.4, 0.6),
                kT(90, -4.6, 8.5, 3.6, 0.6)));
        // 뱀파이어 각성 '혈광 해방' (80틱, 참고: 대전 시작·각성 포즈): 고개 숙인 얼굴 앞 → 낮은 앞에서 포효를 올려다봄 →
        //   옆에서 팔을 휘둘러 올리는 모습 → 주저앉아 고개를 돌린 쪽(왼앞)에서 '바-ン' 포즈로 천천히 다가감
        // 뱀파이어 각성 '혈광 해방' (116틱 = 자료 영상 첫 등장 장면 5.8초, 같은 구도·흐름):
        //   만화 칸 속 전신 (등 뒤 비스듬히, 어깨 너머로 돌아봄) → 얼굴 옆 클로즈업 (고개를 젖혀 외침 + 대사) →
        //   가슴을 쓸어내리는 손 → 발밑에서 올려다보는 구도로 아주 천천히 다가감 (두 팔을 늘어뜨리고 내려다봄)
        SHOTS.put("vm_awaken", new Shot(116, 10, true, null, null, 0,
                new Line[]{new Line(18, "해는 저물었다."), new Line(64, " 네놈의 생명도 저물 때다!")},   // 음성(각성.ogg)의 쉼 1.2~2.3초에 맞춤
                cut(0, -2.6, 1.3, 1.1, 0, 1.2, 0),
                k(17, -2.3, 1.35, 1.25, 0, 1.25, 0),
                cut(18, 0.35, 1.55, 0.95, 0.1, 1.68, 0),
                k(33, 0.3, 1.5, 0.85, 0.1, 1.72, 0),
                cut(34, 1.3, 1.25, 0.55, 0, 1.15, 0),
                k(39, 1.2, 1.25, 0.5, 0, 1.1, 0),
                cut(40, 1.25, 0.15, 0.35, 0, 1.75, 0),
                k(112, 0.95, 0.2, 0.25, 0, 1.75, 0)));
        // 뱀파이어 각성기 '피의 만찬' (290틱 ≈ 자료 영상 1부 디오 GHA 14.5초, 같은 흐름 — 대사·구도는 우리 것):
        //   기운이 터짐 → 웃는 얼굴 클로즈업 → 카메라 쪽으로 손끝을 내지름 → 옆에서 낮게 날아드는 모습 → 낮은 뒤에서 얼어 들어 올려지는 상대 →
        //   웅크려 웃는 얼굴(대사 1) → 낮은 옆에서 올려치기 → 웃는 얼굴(대사 2) → 낮은 뒤·옆에서 연타 → 뛰어오름을 올려다봄 → 바로 위에서 착지 →
        //   올려다보는 얼굴(대사 3) → 손끝 클로즈업 → 낮은 옆에서 마지막 찌르기·산산조각 → 선 모습 → 웃는 얼굴
        SHOTS.put("vm_ult", new Shot(290, 10, true, null, null, 0,
                new Line[]{new Line(46, "조금이라도 내게 이길 줄 알았더냐! 머저리!"), new Line(128, "|빈약하다 빈약해..."), new Line(190, "|죽음의 망각을 맞이해라!")},   // 음성 시작 틱과 같음
                cut(0, 2.3, 1.2, 0.6, 0, 1.1, 0),
                cut(6, 0.75, 1.6, 0.28, 0, 1.6, 0),
                k(20, 0.65, 1.62, 0.22, 0, 1.62, 0),
                cut(21, 1.7, 1.15, -0.5, 0, 1.3, 0),
                k(35, 1.35, 1.2, -0.4, 0, 1.3, 0),
                cut(36, 1.0, 0.8, 3.4, 1.4, 0.7, 0),
                k(46, 2.2, 0.9, 3.0, 1.6, 0.9, 0),
                cutT(47, -1.8, 0.4, 1.0, 1.4),
                kT(59, -2.1, 0.35, 1.3, 1.9),
                cut(60, 1.0, 1.0, 0.35, 0, 1.3, 0),
                k(85, 0.85, 1.05, 0.3, 0, 1.3, 0),
                cut(86, 1.2, 0.5, 2.6, 1.0, 1.9, 0),
                k(108, 1.4, 0.55, 3.0, 1.0, 1.9, 0),
                cut(109, 0.9, 1.5, 0.4, 0, 1.6, 0),
                k(133, 0.8, 1.55, 0.3, 0, 1.6, 0),
                cutT(134, -1.4, 0.25, 1.1, 1.7),
                kT(150, -1.6, 0.25, 1.5, 1.9),
                cut(151, 1.0, 0.5, 3.0, 1.0, 1.8, 0),
                k(170, 1.3, 0.6, 3.4, 1.0, 2.0, 0),
                cutT(171, -2.6, 0.3, 1.4, 2.6),
                kT(181, -2.9, 0.3, 1.6, 3.2),
                cut(182, 0.3, 7.0, 0.3, 0.3, 0, 0),
                k(189, 0.3, 6.4, 0.3, 0.3, 0, 0),
                cut(190, 1.0, 1.25, 0.3, 0, 1.45, 0),
                k(228, 0.85, 1.3, 0.25, 0, 1.5, 0),
                cut(229, 1.4, 1.5, 0.9, 0, 1.7, 0),
                k(237, 1.2, 1.6, 0.7, 0, 1.8, 0),
                cutT(238, 0.5, 0.25, 2.8, 2.2),
                kT(256, 0.3, 0.3, 3.4, 1.8),
                cut(257, 2.6, 1.0, 0.6, 0, 1.4, 0),
                k(270, 2.0, 1.2, 0.4, 0, 1.5, 0),
                cut(271, 0.9, 1.55, 0.2, 0, 1.6, 0),
                k(290, 0.75, 1.6, 0.15, 0, 1.65, 0)));

        // 기둥 속 사내 · 열 각성 '비등점' (84틱, 참고 '너어어무해에에에~!' 0:29.8~0:34.3): 움찔하는 전신 →
        //   고개를 젖혀 울부짖는 얼굴 아주 가까이 (아래 옆에서) → 낮은 옆 앞에서 팔뚝을 얼굴에 대고 우는 상반신을 천천히 돌아봄 → 열이 끓어오르는 전신
        SHOTS.put("ph_awaken", new Shot(84, 10, true, "비등점", "기둥 속 사내 · 각성", 74,
                new Line[]{new Line(6, "너어어무해에에에에에~!!")},
                cut(0, 2.4, 1.0, 0.8, 0, 1.1, 0),
                cut(5, 0.55, 1.35, 0.55, 0, 1.78, 0),
                k(20, 0.5, 1.3, 0.45, 0, 1.8, 0),
                cut(21, 1.5, 0.7, -1.3, 0, 1.45, 0),
                k(50, 1.7, 0.8, -0.4, 0, 1.5, 0),
                k(70, 1.6, 0.9, 0.5, 0, 1.5, 0),
                cut(71, 3.2, 1.0, 1.2, 0, 1.2, 0),
                k(84, 2.8, 1.2, 0.9, 0, 1.3, 0)));
        // 기둥 속 사내 · 열 각성기 '증기 폭발' (256틱) — 참고 ASBR 에시디시 트레일러 45.6~58.4초를 1틱 단위로 떼어 구도를 맞춤 (상대는 서버가 앞 1.4칸에 붙였다가 66~72 에 4.5칸으로 밀어냄):
        //   0~8 시전자 오른쪽 뒤 어깨 너머 (움켜쥠) → 9~16 만화 칸: 옆얼굴 → 17~33 만화 칸: 앞 아래에서 V 로 벌린 팔 (혈관 터짐) → 34~49 만화 칸: 얼굴 앞 (갈고리 손) →
        //   50~65 옆 (혈관이 꽂힘) → 66~72 뒤 낮게 → 73~109 상대 어깨 너머 → 110~119 옆 멀리 낮게 → 120~135 앞 아래 가까이 (펌프) → 136~153 위에서 얼굴 →
        //   154~189 혈관을 따라 → 190~237 상대 어깨 너머 (타는 상대) → 238~ 옆 앞에서 대폭발
        SHOTS.put("ph_ult", new Shot(256, 10, true, null, null, 0,
                new Line[]{new Line(12, "열을 다루는 유법!"), new Line(62, "|내 열혈을 흘려 넣어… 불을 붙여 태워주마!"), new Line(238, "|다 태워 버렸다!")},
                cut(0, -1.6, 1.4, 1.8, 0.7, 1.2, 0),
                k(8, -1.5, 1.4, 1.7, 0.7, 1.2, 0),
                cut(9, 0.55, 1.45, -1.3, 0.45, 1.45, 0),
                k(16, 0.5, 1.45, -1.25, 0.45, 1.47, 0),
                cut(17, 1.0, 0.6, 1.0, 0.0, 1.6, 0),
                k(33, 0.95, 0.62, 0.95, 0.0, 1.62, 0),
                cut(34, 1.0, 1.5, 0.45, -0.05, 1.55, 0),
                k(49, 0.97, 1.5, 0.42, -0.05, 1.55, 0),
                cut(50, 0.7, 1.1, 2.6, 0.7, 1.0, 0),
                k(65, 0.8, 1.1, 2.5, 0.8, 1.0, 0),
                cutT(66, -1.4, 0.5, 1.3, 1.0),
                kT(72, -1.5, 0.5, 1.35, 1.0),
                cut(73, 5.7, 1.1, 0.9, 1.0, 1.0, 0),
                k(109, 5.5, 1.05, 0.85, 1.0, 1.0, 0),
                cut(110, 2.25, 0.5, 4.0, 2.25, 1.0, 0),
                k(119, 2.25, 0.5, 3.8, 2.25, 1.0, 0),
                cut(120, 1.3, 0.8, 0.3, 0.0, 1.3, 0),
                k(135, 1.2, 0.8, 0.3, 0.0, 1.3, 0),
                cut(136, 0.75, 2.2, 0.2, 0.0, 1.55, 0),
                k(153, 0.7, 2.15, 0.2, 0.0, 1.55, 0),
                cutT(154, 1.0, 1.0, 0.6, 1.0),
                kT(189, 1.2, 1.0, 0.7, 1.0),
                cut(190, 5.7, 1.1, 0.9, 1.0, 1.0, 0),
                k(237, 5.5, 1.05, 0.85, 1.0, 1.0, 0),
                cutT(238, 3.0, 0.6, -2.2, 1.5),
                kT(256, 2.8, 0.7, -2.5, 1.8)));
        // 기둥 속 사내 '눈 깜짝할 사이의 일격' 시작: 버티고 서서 노려보는 상반신 클로즈업 (내 화면만, 9틱)
        SHOTS.put("ph_blink", new Shot(9, 3, true, null, null, 0, NO_LINES,
                cut(0, 1.1, 1.2, 0.5, 0, 1.45, 0),
                k(9, 0.95, 1.25, 0.45, 0, 1.5, 0)));
        // 기둥 속 사내 · 바람 각성 (64틱, 참고 '「바람」만을 느껴 사물을 보겠다' 0:42.8~0:45.2): 전신 → 손가락을 눈에 대는 얼굴 옆 클로즈업 →
        //   감긴 눈 정면 → 아래에서 올려다본 머리 (뿔에 바람이 감겨 솟음) → 전신
        SHOTS.put("pw_awaken", new Shot(64, 10, true, "바람만을 느껴", "기둥 속 사내 · 각성", 52,
                new Line[]{new Line(4, "이제부터는… 빛이 아니라 「바람」만을 느껴 사물을 보겠다")},
                cut(0, 2.4, 1.1, 0.8, 0, 1.2, 0),
                cut(5, 0.55, 1.65, 0.45, 0, 1.68, 0),
                k(24, 0.5, 1.62, 0.4, 0, 1.7, 0),
                cut(25, 0.6, 1.62, 0.0, 0, 1.7, 0),
                k(33, 0.52, 1.6, 0.0, 0, 1.72, 0),
                cut(34, 0.9, 0.9, -0.6, 0, 2.0, 0),
                k(48, 1.0, 0.7, -0.8, 0, 2.4, 0),
                cut(49, 3.0, 1.3, 1.2, 0, 1.2, 0),
                k(64, 3.4, 1.5, 1.0, 0, 1.2, 0)));
        // 기둥 속 사내 · 바람 각성기 '최종 유법' (170틱) — 참고 ASBR 79.3~87.8초를 1틱(0.05초) 단위로 떼어 구도를 맞춤 (상대는 서버가 앞 3.2칸에 세움, 60~66 에 3칸 떠오름):
        //   0~20 옆 멀리 둘 다 (가슴 높이) → 21~37 만화 칸: 얼굴 앞 아래 왼쪽 → 38~49 만화 칸: 바로 앞 아래에서 젖혀진 머리 →
        //   50~63 다시 옆 멀리 (던지기·몸 날리기) → 64~121 뒤 낮은 곳에서 떠오른 상대를 올려봄 (천천히 돎) → 122~125 뿔 클로즈업 (외침) →
        //   126~129 앞 왼쪽 가까이 → 130~139 상대 쪽 낮은 곳에서 돌아봄 → 140~143 바로 앞 낮게 (주저앉음) → 144~ 상대 어깨 너머 높은 곳에서 내려봄 (회오리)
        SHOTS.put("pw_ult", new Shot(170, 10, true, null, null, 0,
                new Line[]{new Line(16, "최종 유법 「혼설삽」!"), new Line(120, "|쉬이아아아아아—!!")},
                cut(0, 1.5, 1.0, 2.3, 1.5, 0.95, 0),
                k(20, 1.4, 1.0, 2.1, 1.4, 0.95, 0),
                cut(21, 1.25, 1.25, -0.45, 0.1, 1.6, 0),
                k(37, 1.2, 1.27, -0.4, 0.1, 1.62, 0),
                cut(38, 1.1, 1.4, 0.5, -0.1, 1.72, 0),
                k(49, 1.05, 1.38, 0.45, -0.1, 1.75, 0),
                cut(50, 1.7, 1.0, 2.4, 1.7, 0.9, 0),
                k(63, 1.8, 0.95, 2.3, 1.9, 0.8, 0),
                cutT(64, -3.4, 0.6, -1.5, 0.6),
                kT(92, -3.7, 0.65, -0.9, 0.6),
                kT(121, -3.4, 0.7, -1.5, 0.6),
                cut(122, 0.95, 1.4, -0.4, 0.3, 1.65, 0),
                k(125, 0.9, 1.4, -0.38, 0.3, 1.66, 0),
                cut(126, 1.9, 1.05, -1.2, 0.3, 1.3, 0),
                k(129, 1.85, 1.05, -1.15, 0.3, 1.3, 0),
                cut(130, 1.75, 0.45, -0.8, 0.0, 1.15, 0),
                k(139, 1.7, 0.45, -0.75, 0.0, 1.15, 0),
                cut(140, 1.7, 0.5, 0.2, 0.0, 0.95, 0),
                k(143, 1.6, 0.5, 0.2, 0.0, 0.95, 0),
                cut(144, 4.9, 2.5, -1.3, 0.0, 0.6, 0),
                k(156, 4.7, 2.4, -1.2, 0.0, 0.6, 0),
                k(170, 5.2, 2.8, -1.4, 0.0, 0.8, 0)));
        // 기둥 속 사내 · 빛 각성 '빛 칼날' (64틱, 참고 2:10~2:12.5 등장 · 3:39 칼날): 앞 전신 → 손을 편 얼굴 가까이 (6~25) → 칼날이 돋는 전신 (낮은 앞) →
        //   칼날을 세워 비춰 보는 상반신 → 천천히 물러남
        SHOTS.put("pl_awaken", new Shot(64, 10, true, "빛 칼날", "기둥 속 사내 · 각성", 40,
                new Line[]{new Line(4, "나의 유법은 빛… 「빛 칼날」의 유법!")},
                cut(0, 2.6, 1.1, 0.6, 0, 1.1, 0),
                cut(6, 0.9, 1.55, 0.2, 0, 1.55, 0),
                k(25, 0.8, 1.58, 0.15, 0, 1.57, 0),
                cut(26, 2.4, 0.4, -0.8, 0, 1.0, 0),
                k(34, 2.6, 0.5, -0.9, 0, 1.0, 0),
                cut(35, 1.1, 1.4, 0.5, 0, 1.45, 0),
                k(50, 1.0, 1.45, 0.4, 0, 1.5, 0),
                cut(51, 2.4, 1.2, 1.0, 0, 1.1, 0),
                k(64, 3.0, 1.3, 1.2, 0, 1.1, 0)));
        // 기둥 속 사내 · 빛 각성기 '섬광 난도' (100틱) — 참고 ASBR 카즈 '모드「휘채활도」' 3:39.6~3:44.6 을 1틱 단위로 떼어 구도를 맞춤
        //   (상대는 서버가 앞 3칸에 세움, 시전자가 28~31 에 2칸 · 60~66 에 5칸까지 나가 상대를 꿰뚫고 지나감 — 카메라는 시전자를 따라감):
        //   0~5 옆 둘 다 → 6~10 가슴 앞 아래 (칼날을 세움) → 11~25 얼굴 가까이 (칼날을 핥음) → 26~31 옆 뒤에서 내달림 → 32~50 옆 (빛의 호 연속) →
        //   51~69 왼쪽 낮게 (낮게 쓸어 벰, 꿰뚫고 지나감) → 70~80 앞 아래 (칼날을 하늘로) → 81~ 앞 오른쪽 (걸어 나오고 등 뒤에서 상대가 늦게 베임)
        SHOTS.put("pl_ult", new Shot(100, 10, true, null, null, 0,
                new Line[]{new Line(11, "유법 「빛 칼날」!"), new Line(74, "|하찮군.")},
                cut(0, 1.6, 1.0, 3.2, 1.5, 1.0, 0),
                k(5, 1.6, 1.0, 3.1, 1.5, 1.0, 0),
                cut(6, 1.15, 1.0, 0.3, 0.0, 1.35, 0),
                k(10, 1.05, 1.05, 0.25, 0.0, 1.4, 0),
                cut(11, 1.0, 1.48, -0.3, 0.0, 1.55, 0),
                k(25, 0.9, 1.5, -0.25, 0.0, 1.56, 0),
                cut(26, -1.0, 1.3, 3.0, 2.0, 1.0, 0),
                k(31, -0.6, 1.3, 3.2, 2.4, 1.0, 0),
                cut(32, 0.6, 1.2, 2.9, 0.6, 1.0, 0),
                k(50, 0.8, 1.2, 2.7, 0.7, 1.0, 0),
                cut(51, 1.3, 0.45, -2.3, 0.0, 0.75, 0),
                k(65, 1.2, 0.45, -2.2, 0.0, 0.8, 0),
                k(66, 1.4, 0.45, -2.2, 0.6, 0.8, 0),
                cut(70, 2.0, 0.3, -0.6, 0.0, 1.5, 0),
                k(80, 1.9, 0.35, -0.55, 0.0, 1.6, 0),
                cut(81, 2.6, 1.0, 1.5, -0.9, 1.1, 0),
                k(100, 2.3, 1.0, 1.4, -1.0, 1.1, 0)));
        // 가시밭 마무리: 시전자 뒤 낮은 곳에서 가시 숲 위로 날아가는 대상을 올려다봄
        SHOTS.put("al_forest", new Shot(22, 7, true, null, null, 0, NO_LINES,
                cutT(0, -1.7, 0.7, 0.7, 1.0),
                kT(16, -2.4, 0.5, 1.1, 1.0)));
    }

    private static Shot shot;
    private static int actorId, targetId;
    private static String shotName = "";
    private static double clock;   // 클라이언트 틱
    private static double start;
    private static float baseYaw, basePitch;
    private static Vec3d lastActor, lastTarget;

    public static void init() {
        ClientPlayNetworking.registerGlobalReceiver(CutscenePayload.ID, (p, ctx) -> ctx.client().execute(() -> {
            if (p.shot().startsWith("#music:")) music(ctx.client(), p.shot().substring(7));
            else if (p.shot().startsWith("#sound:")) voice(ctx.client(), p.shot().substring(7));
            else if (p.shot().startsWith("#windsight:")) WindSight.set(p.shot().endsWith(":on"));
            else if (p.shot().equals("#music_stop")) music(ctx.client(), null);
            else play(ctx.client(), p);
        }));
        ClientTickEvents.END_CLIENT_TICK.register(c -> {
            clock++;
            if (shot != null && clock - start > shot.len()) shot = null;
            // 시전자 본인이면 컷신 동안 시선을 고정 (마우스로 돌려도 몸이 카메라 대본에서 벗어나지 않게)
            if (shot != null && shot.lock() && c.player != null && c.player.getId() == actorId) {
                c.player.setYaw(baseYaw);
                c.player.setPitch(basePitch);
            }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((h, c) -> { shot = null; music(c, null); });
        HudRenderCallback.EVENT.register((ctx, counter) -> hud(ctx, counter.getTickDelta(false)));
    }

    private static void play(MinecraftClient c, CutscenePayload p) {
        Shot s = SHOTS.get(p.shot());
        if (s == null || c.world == null) return;
        Entity a = c.world.getEntityById(p.actor());
        if (a == null) return;
        shot = s;
        shotName = p.shot();
        actorId = p.actor();
        targetId = p.target();
        start = clock;
        baseYaw = a.getYaw();
        basePitch = a.getPitch();
        lastActor = a.getPos();
        Entity t = targetId >= 0 ? c.world.getEntityById(targetId) : null;
        lastTarget = t != null ? t.getPos() : null;
    }

    /** 각성 테마곡 등: 서버가 "#music:<소리 id>" / "#music_stop" 으로 보냄. 바닐라 배경음은 멈추고 음악 볼륨으로 틂 */
    private static net.minecraft.client.sound.SoundInstance musicNow;
    private static void music(MinecraftClient c, String id) {
        if (musicNow != null) { c.getSoundManager().stop(musicNow); musicNow = null; }
        if (id == null) return;
        Identifier sid = Identifier.tryParse(id);
        if (sid == null) return;
        c.getMusicTracker().stop();
        // '음반' 소리 설정으로 (음악 볼륨을 꺼 둔 사람이 많아 '음악' 으로 틀면 안 들렸음). 화면 기준으로 들림
        musicNow = new net.minecraft.client.sound.PositionedSoundInstance(sid, net.minecraft.sound.SoundCategory.RECORDS, 0.9f, 1.0f,
                net.minecraft.client.sound.SoundInstance.createRandom(), false, 0, net.minecraft.client.sound.SoundInstance.AttenuationType.NONE, 0, 0, 0, true);
        c.getSoundManager().play(musicNow);
    }

    /** 컷신 대사: 카메라가 멀리 있어도 작아지지 않게 화면 기준으로 (서버가 "#sound:<id>" 로 보냄) */
    private static void voice(MinecraftClient c, String id) {
        Identifier sid = Identifier.tryParse(id);
        if (sid == null) return;
        c.getSoundManager().play(new net.minecraft.client.sound.PositionedSoundInstance(sid, net.minecraft.sound.SoundCategory.PLAYERS, 1.0f, 1.0f,
                net.minecraft.client.sound.SoundInstance.createRandom(), false, 0, net.minecraft.client.sound.SoundInstance.AttenuationType.NONE, 0, 0, 0, true));
    }

    public static boolean active() { return shot != null; }
    public static boolean locked() { return shot != null && shot.lock(); }

    private static double time(float delta) { return clock - start + delta; }

    private static Vec3d frame(Vec3d base, double f, double u, double r) {
        double yr = Math.toRadians(baseYaw);
        double fx = -Math.sin(yr), fz = Math.cos(yr);   // 앞
        double rx = -Math.cos(yr), rz = -Math.sin(yr);  // 오른쪽
        return base.add(fx * f + rx * r, u, fz * f + rz * r);
    }

    private static Vec3d[] eval(Key key, Vec3d actor, Vec3d target) {
        Vec3d cam = frame(actor, key.f(), key.u(), key.r());
        Vec3d look = key.lookTarget() && target != null ? target.add(0, key.lu(), 0)
                : frame(actor, key.lookTarget() ? 2.5 : key.lf(), key.lu(), key.lookTarget() ? 0 : key.lr());
        return new Vec3d[]{cam, look};
    }

    /** CameraMixin 이 부름: 원래 카메라(pos, yaw, pitch)를 받아 컷신 카메라 {x, y, z, yaw, pitch} 를 돌려줌. 컷신이 아니면 null */
    public static double[] camera(Vec3d vPos, float vYaw, float vPitch, float delta) {
        Shot s = shot;
        MinecraftClient c = MinecraftClient.getInstance();
        if (s == null || c.world == null) return null;
        double t = time(delta);
        if (t > s.len()) return null;
        Entity a = c.world.getEntityById(actorId);
        if (a != null) lastActor = a.getLerpedPos(delta);
        Entity tg = targetId >= 0 ? c.world.getEntityById(targetId) : null;
        if (tg != null) lastTarget = tg.getLerpedPos(delta);
        Key[] ks = s.keys();
        int i = 0;
        while (i + 1 < ks.length && ks[i + 1].t() <= t) i++;
        Vec3d[] cur = eval(ks[i], lastActor, lastTarget);
        if (i + 1 < ks.length && !ks[i + 1].cut()) {
            Vec3d[] nxt = eval(ks[i + 1], lastActor, lastTarget);
            double f = smooth((t - ks[i].t()) / Math.max(1e-3, ks[i + 1].t() - ks[i].t()));
            cur = new Vec3d[]{cur[0].lerp(nxt[0], f), cur[1].lerp(nxt[1], f)};
        }
        Vec3d d = cur[1].subtract(cur[0]);
        double yaw = Math.toDegrees(Math.atan2(-d.x, d.z));
        double pitch = -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
        Vec3d pos = cur[0];
        double bo = s.len() - s.blendOut();
        if (t > bo) {   // 원래 카메라로 돌아옴
            double w = smooth((t - bo) / s.blendOut());
            pos = pos.lerp(vPos, w);
            yaw = yaw + MathHelper.wrapDegrees(vYaw - yaw) * w;
            pitch = pitch + (vPitch - pitch) * w;
        }
        return new double[]{pos.x, pos.y, pos.z, yaw, pitch};
    }

    private static double smooth(double x) { x = MathHelper.clamp(x, 0, 1); return x * x * (3 - 2 * x); }

    /** 위아래 검은 띠 + 기술 이름 */
    private static void hud(DrawContext ctx, float delta) {
        Shot s = shot;
        if (s == null) return;
        double t = time(delta);
        int W = ctx.getScaledWindowWidth(), H = ctx.getScaledWindowHeight();
        double in = smooth(t / 5.0), out = smooth((s.len() - t) / 6.0);
        int bar = (int) Math.round(H * 0.13 * Math.min(in, out));
        int[][] ps = PANELS.get(shotName);
        if (ps != null) for (int[] p : ps) if (t >= p[0] && t < p[1]) bar = 0;   // 만화 칸일 땐 검은 띠 대신 칸 테두리
        if (bar > 0) {
            ctx.fill(0, 0, W, bar, 0xFF000000);
            ctx.fill(0, H - bar, W, H, 0xFF000000);
        }
        panels(ctx, t, W, H);
        if (s.lines().length > 0) subtitle(ctx, s, t, W, H, bar, out);
        if (s.title() == null || t < s.titleAt()) return;
        double a = Math.min(smooth((t - s.titleAt()) / 4.0), out);
        int alpha = (int) (a * 255);
        if (alpha < 8) return;
        var tr = MinecraftClient.getInstance().textRenderer;
        // 기술 이름: 아래 띠 위쪽에 크게, 금색. 살짝 오른쪽에서 밀려 들어옴
        float slide = (float) ((1 - smooth((t - s.titleAt()) / 6.0)) * 24);
        ctx.getMatrices().push();
        float sc = 2.4f;
        Text title = Text.literal(s.title());
        ctx.getMatrices().translate(W / 2f + slide, H - bar - 34, 0);
        ctx.getMatrices().scale(sc, sc, 1);
        int tw = tr.getWidth(title);
        ctx.drawText(tr, title, -tw / 2 + 1, 1, (alpha << 24) | 0x3A2A08, false);
        ctx.drawText(tr, title, -tw / 2, 0, (alpha << 24) | 0xF0C850, false);
        ctx.getMatrices().pop();
        if (s.sub() != null) {
            Text sub = Text.literal(s.sub());
            ctx.drawText(tr, sub, (int) (W / 2f + slide) - tr.getWidth(sub) / 2, H - bar - 10, (alpha << 24) | 0xE6E6E6, true);
        }
        // 금색 가는 줄
        int lw = (int) (W * 0.28 * a);
        ctx.fill(W / 2 - lw, H - bar - 13, W / 2 + lw, H - bar - 12, (alpha << 24) | 0x8C6A1E);
    }

    /** 만화 칸 연출 (참고 영상 첫 등장·각성기 도입처럼 화면이 만화 쪽 칸으로 나뉨): 컷신 이름 → {시작, 끝, 모양} (0 = 양옆에 칸, 1 = 왼쪽에 좁은 칸) */
    private static final Map<String, int[][]> PANELS = Map.of(
            "vm_awaken", new int[][]{{0, 18, 0}, {18, 34, 1}},
            "vm_ult", new int[][]{{6, 21, 1}, {21, 36, 0}},
            "ph_ult", new int[][]{{9, 17, 1}, {17, 34, 0}, {34, 50, 1}},
            "pw_ult", new int[][]{{21, 38, 0}, {38, 50, 1}},
            "pl_ult", new int[][]{{11, 26, 1}});

    private static void panels(DrawContext ctx, double t, int W, int H) {
        int[][] ps = PANELS.get(shotName);
        if (ps == null) return;
        for (int[] p : ps) {
            if (t < p[0] || t >= p[1]) continue;
            double in = smooth((t - p[0]) / 3.0);
            int paper = 0xFFF2EEE4, ink = 0xFF101010, g = 6;
            int Lw = p[2] == 0 ? (int) (W * 0.22) : (int) (W * 0.07), Rw = p[2] == 0 ? (int) (W * 0.2) : 0;
            int lx = (int) (-Lw * (1 - in)), rx = W - Rw + (int) (Rw * (1 - in));
            // 종이 여백 (위아래 + 칸 사이)
            ctx.fill(0, 0, W, g, paper);
            ctx.fill(0, H - g, W, H, paper);
            // 왼쪽 칸: 누런 종이에 먹 선 몇 개 (그림 칸 느낌)
            ctx.fill(lx, 0, lx + Lw + g, H, paper);
            ctx.fill(lx + g, g, lx + Lw, H - g, 0xFFE6D8B4);
            for (int i = 0; i < 7; i++) {
                float y0 = g + (H - 2 * g) * (0.12f + i * 0.12f);
                Ui.quad(ctx, lx + g + 4, y0, lx + Lw - 4, y0 - Lw * 0.35f, lx + Lw - 4, y0 - Lw * 0.35f + 1.5f, lx + g + 4, y0 + 1.5f, 0x90302010);
            }
            outline(ctx, lx + g, g, lx + Lw, H - g, ink, 2);
            // 오른쪽 칸: 흰 종이에 세로 줄 (건물 칸 느낌)
            if (Rw > 0) {
                ctx.fill(rx - g, 0, W, H, paper);
                ctx.fill(rx, g, W - g, H - g, 0xFFF7F7F2);
                for (int i = 1; i < 6; i++) { int x = rx + (W - g - rx) * i / 6; ctx.fill(x, g + 8, x + 1, H - g - 8, 0xA0202020); }
                for (int i = 1; i < 8; i++) { int y = g + (H - 2 * g) * i / 8; ctx.fill(rx + 4, y, W - g - 4, y + 1, 0x70202020); }
                outline(ctx, rx, g, W - g, H - g, ink, 2);
            }
            // 가운데 칸 테두리
            outline(ctx, lx + Lw + g, g, Rw > 0 ? rx - g : W - g, H - g, ink, 3);
        }
    }

    private static void outline(DrawContext ctx, int x0, int y0, int x1, int y1, int col, int w) {
        ctx.fill(x0, y0, x1, y0 + w, col);
        ctx.fill(x0, y1 - w, x1, y1, col);
        ctx.fill(x0, y0, x0 + w, y1, col);
        ctx.fill(x1 - w, y0, x1, y1, col);
    }

    /** 대사 자막: 아이템창·체력칸 위, 흰 글씨. 토막마다 정해진 틱부터 한 글자씩 (틱당 0.6자) 이어 붙음 */
    private static void subtitle(DrawContext ctx, Shot s, double t, int W, int H, int bar, double out) {
        StringBuilder b = new StringBuilder();
        for (Line l : s.lines()) {
            if (t < l.at()) break;
            String tx = l.text();
            if (tx.startsWith("|")) { b.setLength(0); tx = tx.substring(1); }   // '|' 로 시작하면 새 자막 (앞 대사는 지움)
            int n = (int) Math.min(tx.length(), Math.floor((t - l.at()) * 0.6) + 1);
            b.append(tx, 0, n);
        }
        if (b.length() == 0) return;
        int alpha = (int) (255 * Math.max(0, Math.min(1, out)));
        if (alpha < 8) return;
        var tr = MinecraftClient.getInstance().textRenderer;
        Text line = Text.literal(b.toString());
        float sc = 1.5f;
        ctx.getMatrices().push();
        ctx.getMatrices().translate(W / 2f, H - Math.max(bar, 30) - 44, 0);   // 아이템창·체력칸 위
        ctx.getMatrices().scale(sc, sc, 1);
        ctx.drawText(tr, line, -tr.getWidth(line) / 2, 0, (alpha << 24) | 0xFFFFFF, true);
        ctx.getMatrices().pop();
    }
}
