package br.com.tuvani.murilo.mapeamento;

import br.com.tuvani.murilo.modelo.Grupo;
import br.com.tuvani.murilo.sped.Ecd;

import java.math.BigDecimal;

/**
 * Conta analítica do SPED com saldo, já com o grupo do balanço e os caminhos usados pelas regras.
 *
 * @param saldo             saldo final com sinal contábil (devedor positivo)
 * @param origemGrupo       de onde veio o grupo (J100, referencial, natureza...)
 * @param caminhoAglutinacao descrições J100 normalizadas, do topo até a conta
 * @param caminhoConta       descrições I050 normalizadas, do topo até a conta
 */
public record ContaClassificada(Ecd.Conta conta, BigDecimal saldo, Grupo grupo, String origemGrupo,
                                String caminhoAglutinacao, String caminhoConta, String nomeNormalizado) {

    /** Valor como aparece no balanço: positivo para saldo "normal" do grupo. */
    public BigDecimal valorNoBalanco() {
        return grupo.ativo() ? saldo : saldo.negate();
    }
}
