package bbc.combat;

/** tools/make_sfx_font.py 가 만든 파일 (손으로 고치지 말 것). 효과음 단어 = 그림 글꼴 bbc:sfx_jp 의 조각 글자들 */
final class SfxGlyphs {
    private SfxGlyphs() {}
    static final String[] WORDS = {
        "\ue000", // DON ドン!
        "\ue001\uf801\ue002", // BAKI バキッ!
        "\ue003\uf801\ue004", // DOGO ドゴォ!
        "\ue005", // GA ガッ!
        "\ue006\uf801\ue007", // ZUDON ズドォン!!
        "\ue008\uf801\ue009", // DOGON ドゴォン!!
        "\ue00a\uf801\ue00b", // ZAN ザンッ!
        "\ue00c\uf801\ue00d", // ZUBA ズバァッ!
        "\ue00e\uf801\ue00f", // GAGIN ガギィン!!
        "\ue010\uf801\ue011", // BAKIN バキィン!!
        "\ue012\uf801\ue013", // GOGO ゴゴゴゴ
        "\ue014\uf801\ue015", // DODODO ドドドドッ!!
        "\ue016\uf801\ue017", // BUON ブォン!
        "\ue018\uf801\ue019\uf801\ue01a", // DOGOOON ドゴォォォン!!!
        "\ue01b\uf801\ue01c", // MEKYAN メキャン!
        "\ue01d\uf801\ue01e", // GOOA ゴォアッ!
        "\ue01f\uf801\ue020", // DOGUSHA ドグシャア!!
        "\ue021\uf801\ue022", // PIKIN ピキィン!
    };
    static final String[] SHAPES = {
        "\ue023", // RING
        "\ue024", // FLASH
        "\ue025", // STAR
        "\ue026", // CRESCENT
        "\ue027", // SWISH
    };
}
