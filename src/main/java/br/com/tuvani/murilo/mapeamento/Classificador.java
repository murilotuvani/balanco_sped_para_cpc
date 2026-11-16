package br.com.tuvani.murilo.mapeamento;

import br.com.tuvani.murilo.Texto;
import br.com.tuvani.murilo.modelo.Grupo;
import br.com.tuvani.murilo.sped.Ecd;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Decide o grande grupo (AC, ANC, PC, PNC, PL) de cada conta analítica com saldo.
 *
 * <p>Ordem de decisão: (1) o balanço que a própria empresa entregou no SPED (J100, pela
 * aglutinação I052); (2) o código do plano referencial (I051); (3) a natureza e as descrições do
 * plano de contas. Contas de resultado com saldo (exercício não encerrado) vão para o PL.
 * Contas de compensação (natureza 05) e "outras" (09) ficam fora do balanço.</p>
 */
public final class Classificador {

    /** Resultado da classificação de um arquivo. */
    public record Resultado(List<ContaClassificada> contas, List<Ecd.Conta> semGrupo,
                            BigDecimal saldoCompensacaoExcluido) {
    }

    public Resultado classificar(Ecd ecd) {
        List<ContaClassificada> contas = new ArrayList<>();
        List<Ecd.Conta> semGrupo = new ArrayList<>();
        BigDecimal compensacao = BigDecimal.ZERO;

        for (Map.Entry<String, BigDecimal> e : ecd.saldos().entrySet()) {
            BigDecimal saldo = e.getValue();
            if (saldo.signum() == 0) continue;
            Ecd.Conta conta = ecd.contas().get(e.getKey());
            if (conta == null) {
                conta = new Ecd.Conta(e.getKey(), "", true, 0, "", "(conta sem I050)", List.of(), null);
            }
            String nat = conta.natureza();
            if ("05".equals(nat) || "09".equals(nat)) {
                compensacao = compensacao.add(saldo);
                continue;
            }

            String caminhoAgl = caminhoAglutinacao(ecd, conta.codigoAglutinacao());
            String caminhoConta = caminhoConta(ecd, conta);
            Grupo grupo;
            String origem;
            if ("04".equals(nat)) {
                grupo = Grupo.PL;
                origem = "resultado do exercício não encerrado";
            } else {
                grupo = grupoPelaAglutinacao(ecd, conta.codigoAglutinacao());
                origem = "J100";
                if (grupo == null) {
                    grupo = conta.referenciais().stream().map(Grupo::doReferencial)
                            .filter(g -> g != null).findFirst().orElse(null);
                    origem = "referencial";
                }
                if (grupo == null) {
                    grupo = grupoPelaDescricao(caminhoConta, nat);
                    origem = "plano de contas";
                }
            }
            if (grupo == null) {
                semGrupo.add(conta);
                continue;
            }
            contas.add(new ContaClassificada(conta, saldo, grupo, origem, caminhoAgl, caminhoConta,
                    Texto.normalizar(conta.nome())));
        }
        contas.sort((a, b) -> a.conta().codigo().compareTo(b.conta().codigo()));
        return new Resultado(contas, semGrupo, compensacao);
    }

    /** Descrições das aglutinações totalizadoras, do topo até a conta, separadas por " > ". */
    static String caminhoAglutinacao(Ecd ecd, String codigo) {
        Deque<String> partes = new ArrayDeque<>();
        Set<String> vistos = new HashSet<>();
        String c = codigo;
        while (c != null && !c.isEmpty() && vistos.add(c)) {
            Ecd.Aglutinacao a = ecd.aglutinacoes().get(c);
            if (a == null) break;
            if (a.totalizadora()) partes.addFirst(Texto.normalizar(a.descricao()));
            c = a.codigoSuperior();
        }
        return String.join(" > ", partes);
    }

    static String caminhoConta(Ecd ecd, Ecd.Conta conta) {
        Deque<String> partes = new ArrayDeque<>();
        Set<String> vistos = new HashSet<>();
        Ecd.Conta c = conta;
        while (c != null && vistos.add(c.codigo())) {
            partes.addFirst(Texto.normalizar(c.nome()));
            c = c.codigoSuperior() == null || c.codigoSuperior().isEmpty() ? null : ecd.contas().get(c.codigoSuperior());
        }
        return String.join(" > ", partes);
    }

    /** Sobe pela árvore do J100 e devolve o grupo do nó mais alto que se identifica como AC/ANC/PC/PNC/PL. */
    static Grupo grupoPelaAglutinacao(Ecd ecd, String codigo) {
        Grupo achado = null;
        Set<String> vistos = new HashSet<>();
        String c = codigo;
        while (c != null && !c.isEmpty() && vistos.add(c)) {
            Ecd.Aglutinacao a = ecd.aglutinacoes().get(c);
            if (a == null) break;
            String d = Texto.normalizar(a.descricao());
            if (d.contains("COMPENSAC")) return null;
            Grupo g = grupoDaDescricao(d, a.grupo());
            if (g != null) achado = g; // continua subindo: vale o nível mais alto
            c = a.codigoSuperior();
        }
        return achado;
    }

    private static Grupo grupoPelaDescricao(String caminho, String natureza) {
        if ("03".equals(natureza)) return Grupo.PL;
        String indGrupo = "01".equals(natureza) ? "A" : "02".equals(natureza) ? "P" : "";
        Grupo achado = null;
        for (String parte : caminho.split(" > ")) {
            Grupo g = grupoDaDescricao(parte, indGrupo);
            if (g != null) {
                achado = g;
                break;
            }
        }
        return achado;
    }

    /** Reconhece descrições como "ATIVO CIRCULANTE", "PASSIVO NÃO-CIRCULANTE", "PATRIMÔNIO LÍQUIDO". */
    static Grupo grupoDaDescricao(String d, String indGrupoBal) {
        if (d.contains("PATRIMONIO LIQUIDO") && !d.contains("PASSIVO")) return Grupo.PL;
        boolean naoCirc = d.matches(".*\\bNAO[ -]?CIRCULANTE.*") || d.contains("LONGO PRAZO") || d.contains("PERMANENTE");
        boolean circ = !naoCirc && d.matches(".*\\bCIRCULANTE.*");
        boolean ativo = d.startsWith("ATIVO") || "A".equals(indGrupoBal) && !d.startsWith("PASSIVO");
        boolean passivo = d.startsWith("PASSIVO") || "P".equals(indGrupoBal) && !d.startsWith("ATIVO");
        if (naoCirc && ativo) return Grupo.ANC;
        if (naoCirc && passivo) return Grupo.PNC;
        if (circ && ativo) return Grupo.AC;
        if (circ && passivo) return Grupo.PC;
        return null;
    }
}
