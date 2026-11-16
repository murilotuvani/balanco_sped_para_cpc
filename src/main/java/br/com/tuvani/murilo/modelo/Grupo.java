package br.com.tuvani.murilo.modelo;

/** Grandes grupos do balanço, identificados pelo prefixo do código da linha no modelo. */
public enum Grupo {
    AC("1.1", true, "Ativo Circulante"),
    ANC("1.2", true, "Ativo Não Circulante"),
    PC("2.1", false, "Passivo Circulante"),
    PNC("2.2", false, "Passivo Não Circulante"),
    PL("2.3", false, "Patrimônio Líquido");

    private final String prefixo;
    private final boolean ativo;
    private final String descricao;

    Grupo(String prefixo, boolean ativo, String descricao) {
        this.prefixo = prefixo;
        this.ativo = ativo;
        this.descricao = descricao;
    }

    public String prefixo() {
        return prefixo;
    }

    /** {@code true} para grupos do ativo (saldo devedor positivo). */
    public boolean ativo() {
        return ativo;
    }

    public String descricao() {
        return descricao;
    }

    /** Grupo de um código de linha do modelo (ex.: 1.2.03.01 -> ANC), ou {@code null}. */
    public static Grupo doCodigoLinha(String codigo) {
        for (Grupo g : values()) {
            if (codigo.equals(g.prefixo) || codigo.startsWith(g.prefixo + ".")) {
                return g;
            }
        }
        return null;
    }

    /** Grupo a partir de um código do plano referencial da RFB (1.01, 1.02, 2.01, 2.02, 2.03). */
    public static Grupo doReferencial(String ref) {
        if (ref == null) {
            return null;
        }
        if (ref.startsWith("1.01")) return AC;
        if (ref.startsWith("1.02")) return ANC;
        if (ref.startsWith("2.01")) return PC;
        if (ref.startsWith("2.02")) return PNC;
        if (ref.startsWith("2.03")) return PL;
        return null;
    }
}
