package br.com.tuvani.murilo.mapeamento;

import br.com.tuvani.murilo.modelo.Grupo;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Regra de enquadramento de uma conta numa linha do modelo.
 *
 * @param grupo   grupo em que a regra vale, ou {@code null} para qualquer grupo
 * @param campos  campos comparados (todos precisam casar)
 * @param padroes expressão regular de cada campo, na mesma ordem
 * @param linha   código da linha do modelo de destino
 * @param origem  onde a regra foi definida (arquivo:linha), para auditoria
 */
public record Regra(Grupo grupo, List<Campo> campos, List<Pattern> padroes, String linha, String comentario,
                    String origem) {

    public enum Campo {
        /** Caminho das descrições da aglutinação J100 (só totalizadoras). */
        AGL,
        /** Caminho das descrições do plano de contas I050, incluindo a própria conta. */
        CONTA,
        /** Descrição da conta analítica. */
        NOME,
        /** Código(s) do plano referencial (I051). */
        REF,
        /** Código da conta analítica. */
        COD,
        /** Qualquer conta. */
        TODAS
    }

    public boolean aceita(ContaClassificada c) {
        if (grupo != null && grupo != c.grupo()) {
            return false;
        }
        for (int i = 0; i < campos.size(); i++) {
            if (!casa(campos.get(i), padroes.get(i), c)) {
                return false;
            }
        }
        return true;
    }

    private static boolean casa(Campo campo, Pattern p, ContaClassificada c) {
        return switch (campo) {
            case TODAS -> true;
            case AGL -> p.matcher(c.caminhoAglutinacao()).find();
            case CONTA -> p.matcher(c.caminhoConta()).find();
            case NOME -> p.matcher(c.nomeNormalizado()).find();
            case COD -> p.matcher(c.conta().codigo()).find();
            case REF -> c.conta().referenciais().stream().anyMatch(r -> p.matcher(r).find());
        };
    }
}
