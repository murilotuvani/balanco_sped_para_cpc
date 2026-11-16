package br.com.tuvani.murilo.modelo;

/**
 * Linha do modelo de balanço que recebe valor (linha de detalhe, como 1.1.01 ou 1.2.03.04).
 *
 * @param codigo    código na coluna "Código" do modelo
 * @param descricao descrição na coluna "Conta Contábil"
 * @param linha     índice da linha na planilha (base 0)
 * @param grupo     grande grupo a que pertence
 */
public record LinhaModelo(String codigo, String descricao, int linha, Grupo grupo) {
}
