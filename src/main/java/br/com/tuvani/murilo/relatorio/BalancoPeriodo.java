package br.com.tuvani.murilo.relatorio;

import br.com.tuvani.murilo.mapeamento.Classificador;
import br.com.tuvani.murilo.mapeamento.Enquadramento;
import br.com.tuvani.murilo.mapeamento.Mapeador;
import br.com.tuvani.murilo.modelo.Grupo;
import br.com.tuvani.murilo.modelo.LinhaModelo;
import br.com.tuvani.murilo.modelo.ModeloBalanco;
import br.com.tuvani.murilo.Texto;
import br.com.tuvani.murilo.sped.Ecd;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Balanço de uma data-base: valores por linha do modelo, com a origem de cada valor. */
public final class BalancoPeriodo {

    private final Ecd ecd;
    private final List<Enquadramento> enquadramentos;
    private final Map<String, BigDecimal> valores = new LinkedHashMap<>();
    private final Map<Grupo, BigDecimal> totais = new EnumMap<>(Grupo.class);
    private final Classificador.Resultado classificacao;

    private BalancoPeriodo(Ecd ecd, ModeloBalanco modelo, Classificador.Resultado classificacao,
                           List<Enquadramento> enquadramentos) {
        this.ecd = ecd;
        this.classificacao = classificacao;
        this.enquadramentos = enquadramentos;
        for (LinhaModelo l : modelo.detalhes()) valores.put(l.codigo(), BigDecimal.ZERO);
        for (Grupo g : Grupo.values()) totais.put(g, BigDecimal.ZERO);
        for (Enquadramento e : enquadramentos) {
            BigDecimal v = e.conta().valorNoBalanco();
            valores.merge(e.linha(), v, BigDecimal::add);
            totais.merge(e.conta().grupo(), v, BigDecimal::add);
        }
    }

    public static BalancoPeriodo montar(Ecd ecd, ModeloBalanco modelo, Mapeador mapeador) {
        Classificador.Resultado r = new Classificador().classificar(ecd);
        List<Enquadramento> enq = r.contas().stream().map(mapeador::enquadrar).toList();
        return new BalancoPeriodo(ecd, modelo, r, enq);
    }

    public Ecd ecd() { return ecd; }
    public List<Enquadramento> enquadramentos() { return enquadramentos; }
    public Map<String, BigDecimal> valores() { return valores; }
    public BigDecimal total(Grupo g) { return totais.get(g); }
    public Classificador.Resultado classificacao() { return classificacao; }

    public BigDecimal totalAtivo() {
        return total(Grupo.AC).add(total(Grupo.ANC));
    }

    public BigDecimal totalPassivoPl() {
        return total(Grupo.PC).add(total(Grupo.PNC)).add(total(Grupo.PL));
    }

    /**
     * Totais dos grandes grupos segundo o balanço entregue no SPED (J100), para conferência.
     * Usa o nó mais alto da árvore J100 que se identifica como cada grupo.
     */
    public Map<Grupo, BigDecimal> totaisJ100() {
        return totaisJ100(false);
    }

    /** Como {@link #totaisJ100()}, mas com os saldos iniciais do período informados no J100. */
    public Map<Grupo, BigDecimal> totaisJ100Iniciais() {
        return totaisJ100(true);
    }

    private Map<Grupo, BigDecimal> totaisJ100(boolean inicial) {
        Map<Grupo, BigDecimal> m = new EnumMap<>(Grupo.class);
        for (Ecd.Aglutinacao a : ecd.aglutinacoes().values()) {
            Grupo g = grupoDoNo(a);
            if (g == null) continue;
            Ecd.Aglutinacao pai = ecd.aglutinacoes().get(a.codigoSuperior());
            if (pai != null && grupoDoNo(pai) == g) continue;
            BigDecimal s = inicial ? a.saldoInicial() : a.saldoFinal();
            BigDecimal v = g.ativo() ? s : s.negate();
            m.merge(g, v, BigDecimal::add);
        }
        return m;
    }

    private static Grupo grupoDoNo(Ecd.Aglutinacao a) {
        String d = Texto.normalizar(a.descricao());
        if (d.contains("PATRIMONIO LIQUIDO") && !d.contains("PASSIVO")) return Grupo.PL;
        boolean naoCirc = d.matches(".*\\bNAO[ -]?CIRCULANTE.*");
        boolean circ = !naoCirc && d.matches(".*\\bCIRCULANTE.*");
        boolean ativo = "A".equals(a.grupo());
        if (naoCirc) return ativo ? Grupo.ANC : Grupo.PNC;
        if (circ) return ativo ? Grupo.AC : Grupo.PC;
        return null;
    }
}
