package br.com.tuvani.murilo.mapeamento;

/** Resultado do enquadramento de uma conta: a linha do modelo e a regra que decidiu. */
public record Enquadramento(ContaClassificada conta, String linha, Regra regra) {
}
