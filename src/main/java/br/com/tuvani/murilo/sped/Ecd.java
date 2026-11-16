package br.com.tuvani.murilo.sped;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Conteúdo de um arquivo SPED ECD relevante para montar o balanço.
 *
 * @param arquivo       arquivo de origem
 * @param empresa       nome empresarial (registro 0000)
 * @param cnpj          CNPJ (registro 0000)
 * @param inicio        data inicial da escrituração
 * @param fim           data final da escrituração (data-base do balanço)
 * @param contas        plano de contas (I050/I051/I052) por código
 * @param saldos        saldo final das contas analíticas na data {@code fim} (I155), devedor positivo
 * @param aglutinacoes  balanço patrimonial entregue (J100) por código de aglutinação
 */
public record Ecd(Path arquivo,
                  String empresa,
                  String cnpj,
                  LocalDate inicio,
                  LocalDate fim,
                  Map<String, Conta> contas,
                  Map<String, BigDecimal> saldos,
                  Map<String, Aglutinacao> aglutinacoes) {

    /** Conta do plano de contas (I050), com o referencial (I051) e a aglutinação (I052). */
    public record Conta(String codigo, String natureza, boolean analitica, int nivel,
                        String codigoSuperior, String nome, List<String> referenciais,
                        String codigoAglutinacao) {
    }

    /**
     * Linha do balanço patrimonial entregue (J100).
     *
     * @param totalizadora {@code true} para IND_COD_AGL = T
     * @param grupo        IND_GRP_BAL: A (ativo) ou P (passivo e PL)
     * @param saldoInicial saldo inicial do período, devedor positivo
     * @param saldoFinal   saldo final, devedor positivo
     */
    public record Aglutinacao(String codigo, boolean totalizadora, int nivel, String codigoSuperior,
                              String grupo, String descricao, BigDecimal saldoInicial, BigDecimal saldoFinal) {
    }
}
