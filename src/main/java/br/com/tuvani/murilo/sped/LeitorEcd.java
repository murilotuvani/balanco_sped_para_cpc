package br.com.tuvani.murilo.sped;

import java.io.BufferedReader;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lê um arquivo SPED ECD (leiaute 8 ou posterior) em streaming, guardando só o necessário para o
 * balanço: 0000, I050/I051/I052, I150/I155 do último período e J100. Os lançamentos (I200/I250),
 * que são a maior parte do arquivo, são descartados sem serem interpretados.
 */
public final class LeitorEcd {

    private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("ddMMyyyy");

    private final Charset charset;

    public LeitorEcd(Charset charset) {
        this.charset = charset;
    }

    public Ecd ler(Path arquivo) throws IOException {
        String empresa = null;
        String cnpj = null;
        LocalDate inicio = null;
        LocalDate fim = null;

        Map<String, MutableConta> contas = new LinkedHashMap<>();
        MutableConta ultimaConta = null;
        LocalDate fimPeriodoAtual = null;
        Map<String, BigDecimal> saldos = new HashMap<>();
        Map<String, Ecd.Aglutinacao> aglutinacoes = new LinkedHashMap<>();
        boolean dentroDoBalanco = true; // só o primeiro J005 (demonstração da própria entidade)
        int blocosJ005 = 0;

        try (BufferedReader in = Files.newBufferedReader(arquivo, charset)) {
            String linha;
            while ((linha = in.readLine()) != null) {
                // Linhas que não são registros (ex.: conteúdo binário de anexos J801) são ignoradas.
                if (linha.length() < 6 || linha.charAt(0) != '|' || linha.charAt(5) != '|') {
                    continue;
                }
                String reg = linha.substring(1, 5);
                switch (reg) {
                    case "I200", "I250", "I300", "I310", "I355", "I157", "C050", "C051", "C052",
                         "C150", "C155", "C650" -> {
                        // volumosos e irrelevantes para o balanço
                    }
                    case "0000" -> {
                        String[] f = campos(linha);
                        inicio = data(f[3]);
                        fim = data(f[4]);
                        empresa = f[5].trim();
                        cnpj = f[6].trim();
                    }
                    case "I050" -> {
                        String[] f = campos(linha);
                        ultimaConta = new MutableConta(f[6], f[3], "A".equals(f[4]), inteiro(f[5]), f[7], f[8].trim());
                        contas.put(ultimaConta.codigo, ultimaConta);
                    }
                    case "I051" -> {
                        if (ultimaConta != null) {
                            String ref = ultimoCampo(linha);
                            if (!ref.isEmpty() && !ultimaConta.referenciais.contains(ref)) {
                                ultimaConta.referenciais.add(ref);
                            }
                        }
                    }
                    case "I052" -> {
                        if (ultimaConta != null && ultimaConta.aglutinacao == null) {
                            String agl = ultimoCampo(linha);
                            if (!agl.isEmpty()) {
                                ultimaConta.aglutinacao = agl;
                            }
                        }
                    }
                    case "I150" -> {
                        String[] f = campos(linha);
                        fimPeriodoAtual = data(f[3]);
                    }
                    case "I155" -> {
                        if (fim != null && fim.equals(fimPeriodoAtual)) {
                            String[] f = campos(linha);
                            BigDecimal valor = valor(f[8], f[9]);
                            saldos.merge(f[2], valor, BigDecimal::add);
                        }
                    }
                    case "J005" -> {
                        blocosJ005++;
                        dentroDoBalanco = blocosJ005 == 1;
                    }
                    case "J100" -> {
                        if (dentroDoBalanco) {
                            Ecd.Aglutinacao a = aglutinacao(campos(linha));
                            aglutinacoes.putIfAbsent(a.codigo(), a);
                        }
                    }
                    default -> {
                    }
                }
            }
        }

        if (fim == null) {
            throw new IOException("Arquivo sem registro 0000 válido (não parece um SPED ECD): " + arquivo);
        }
        if (saldos.isEmpty()) {
            throw new IOException("Arquivo sem saldos I155 para o período encerrado em " + fim + ": " + arquivo);
        }

        Map<String, Ecd.Conta> contasFinais = new LinkedHashMap<>();
        contas.forEach((k, c) -> contasFinais.put(k, c.toConta()));
        return new Ecd(arquivo, empresa, cnpj, inicio, fim, contasFinais, saldos, aglutinacoes);
    }

    private static Ecd.Aglutinacao aglutinacao(String[] f) {
        if (f.length >= 12) {
            // Leiaute 8+: |J100|COD_AGL|IND_COD_AGL|NIVEL_AGL|COD_AGL_SUP|IND_GRP_BAL|DESCR|VL_INI|DC_INI|VL_FIN|DC_FIN|
            return new Ecd.Aglutinacao(f[2], "T".equals(f[3]), inteiro(f[4]), f[5], f[6], f[7].trim(), valor(f[8], f[9]), valor(f[10], f[11]));
        }
        // Leiautes antigos: |J100|COD_AGL|NIVEL_AGL|IND_GRP_BAL|DESCR|VL_CTA|IND_DC_BAL|...
        return new Ecd.Aglutinacao(f[2], true, inteiro(f[3]), "", f[4], f[5].trim(), f.length > 9 ? valor(f[8], f[9]) : BigDecimal.ZERO, valor(f[6], f[7]));
    }

    static String[] campos(String linha) {
        return linha.split("\\|", -1);
    }

    private static String ultimoCampo(String linha) {
        String[] f = campos(linha);
        for (int i = f.length - 1; i >= 2; i--) {
            if (!f[i].isBlank()) {
                return f[i].trim();
            }
        }
        return "";
    }

    private static LocalDate data(String s) {
        return LocalDate.parse(s.trim(), DATA);
    }

    private static int inteiro(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Converte valor + indicador D/C em número com sinal (devedor positivo). */
    static BigDecimal valor(String numero, String dc) {
        String n = numero.trim();
        if (n.isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal v = new BigDecimal(n.replace(".", "").replace(',', '.'));
        return "C".equalsIgnoreCase(dc.trim()) ? v.negate() : v;
    }

    private static final class MutableConta {
        final String codigo;
        final String natureza;
        final boolean analitica;
        final int nivel;
        final String superior;
        final String nome;
        final List<String> referenciais = new ArrayList<>(1);
        String aglutinacao;

        MutableConta(String codigo, String natureza, boolean analitica, int nivel, String superior, String nome) {
            this.codigo = codigo;
            this.natureza = natureza;
            this.analitica = analitica;
            this.nivel = nivel;
            this.superior = superior;
            this.nome = nome;
        }

        Ecd.Conta toConta() {
            return new Ecd.Conta(codigo, natureza, analitica, nivel, superior, nome, List.copyOf(referenciais), aglutinacao);
        }
    }
}
