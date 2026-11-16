package br.com.tuvani.murilo;

import br.com.tuvani.murilo.mapeamento.Mapeador;
import br.com.tuvani.murilo.modelo.Grupo;
import br.com.tuvani.murilo.modelo.LinhaModelo;
import br.com.tuvani.murilo.modelo.ModeloBalanco;
import br.com.tuvani.murilo.relatorio.BalancoPeriodo;
import br.com.tuvani.murilo.relatorio.Detalhamento;
import br.com.tuvani.murilo.relatorio.GeradorPlanilha;
import br.com.tuvani.murilo.sped.Ecd;
import br.com.tuvani.murilo.sped.LeitorEcd;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Gera o balanço patrimonial no modelo CPC 26 a partir de arquivos SPED ECD.
 *
 * <pre>
 * java -jar balanco-sped-cpc.jar ECD_ATUAL.txt [ECD_ANTERIOR.txt] [opções]
 *   --saida arquivo.xlsx       planilha gerada (padrão: Balanco_CPC_&lt;cnpj&gt;_&lt;data&gt;.xlsx)
 *   --modelo modelo.xlsx       modelo a preencher (padrão: o modelo embutido)
 *   --mapeamento regras.csv    regras de enquadramento (padrão: as embutidas)
 *   --charset ISO-8859-1       codificação dos arquivos SPED
 *   --sem-detalhamento         não gera o CSV de conferência
 * </pre>
 *
 * A ordem dos arquivos não importa: o de data-base mais recente vira o "Exercício Atual".
 */
public final class App {

    private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DecimalFormat MOEDA = new DecimalFormat("#,##0.00;-#,##0.00", DecimalFormatSymbols.getInstance(Locale.of("pt", "BR")));
    private static final BigDecimal TOLERANCIA = new BigDecimal("0.01");

    public static void main(String[] args) {
        try {
            System.exit(new App().executar(args));
        } catch (IllegalArgumentException e) {
            System.err.println("Erro: " + e.getMessage());
            System.exit(2);
        } catch (Exception e) {
            System.err.println("Erro: " + e);
            e.printStackTrace(System.err);
            System.exit(1);
        }
    }

    int executar(String[] args) throws Exception {
        List<Path> arquivos = new ArrayList<>();
        Path saida = null, modeloPath = null, mapeamentoPath = null;
        Charset charset = StandardCharsets.ISO_8859_1;
        boolean detalhamento = true;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--saida", "-o" -> saida = Path.of(valor(args, ++i, a));
                case "--modelo" -> modeloPath = Path.of(valor(args, ++i, a));
                case "--mapeamento" -> mapeamentoPath = Path.of(valor(args, ++i, a));
                case "--charset" -> charset = Charset.forName(valor(args, ++i, a));
                case "--sem-detalhamento" -> detalhamento = false;
                case "--help", "-h" -> {
                    uso();
                    return 0;
                }
                default -> {
                    if (a.startsWith("--")) throw new IllegalArgumentException("opção desconhecida: " + a);
                    arquivos.add(Path.of(a));
                }
            }
        }
        if (arquivos.isEmpty() || arquivos.size() > 2) {
            uso();
            return 2;
        }
        for (Path p : arquivos) {
            if (!Files.isRegularFile(p)) throw new IllegalArgumentException("arquivo não encontrado: " + p);
        }

        // Modelo e regras.
        byte[] modeloBytes = modeloPath != null ? Files.readAllBytes(modeloPath) : recurso("/modelo/Modelo_Balanco_Patrimonial.xlsx");
        ModeloBalanco modelo;
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(modeloBytes))) {
            modelo = ModeloBalanco.analisar(GeradorPlanilha.abaBalanco(wb));
        }
        Mapeador mapeador;
        try (Reader r = mapeamentoPath != null ? Files.newBufferedReader(mapeamentoPath, StandardCharsets.UTF_8)
                : new InputStreamReader(new ByteArrayInputStream(recurso("/mapeamento-padrao.csv")), StandardCharsets.UTF_8)) {
            mapeador = Mapeador.carregar(r, mapeamentoPath != null ? mapeamentoPath.getFileName().toString() : "mapeamento-padrao.csv", modelo);
        }

        // Leitura dos SPED em paralelo.
        List<Ecd> ecds = new ArrayList<>();
        LeitorEcd leitor = new LeitorEcd(charset);
        try (ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Ecd>> fs = new ArrayList<>();
            for (Path p : arquivos) {
                System.out.println("Lendo " + p.getFileName() + " ...");
                fs.add(ex.submit(() -> leitor.ler(p)));
            }
            for (Future<Ecd> f : fs) {
                try {
                    ecds.add(f.get());
                } catch (ExecutionException e) {
                    throw e.getCause() instanceof Exception c ? c : e;
                }
            }
        }
        ecds.sort(Comparator.comparing(Ecd::fim).reversed());
        Ecd ecdAtual = ecds.get(0);
        Ecd ecdAnterior = ecds.size() > 1 ? ecds.get(1) : null;
        List<String> avisos = new ArrayList<>();
        if (ecdAnterior != null) {
            if (ecdAnterior.fim().equals(ecdAtual.fim())) {
                throw new IllegalArgumentException("os dois arquivos têm a mesma data-base (" + ecdAtual.fim().format(DATA) + ")");
            }
            if (!limpo(ecdAtual.cnpj()).equals(limpo(ecdAnterior.cnpj()))) {
                avisos.add("Os arquivos são de CNPJs diferentes: " + ecdAtual.cnpj() + " e " + ecdAnterior.cnpj());
            }
        }

        BalancoPeriodo atual = BalancoPeriodo.montar(ecdAtual, modelo, mapeador);
        BalancoPeriodo anterior = ecdAnterior == null ? null : BalancoPeriodo.montar(ecdAnterior, modelo, mapeador);

        if (saida == null) {
            saida = Path.of("Balanco_CPC_" + limpo(ecdAtual.cnpj()) + "_" + ecdAtual.fim().format(DateTimeFormatter.BASIC_ISO_DATE) + ".xlsx");
        }
        List<GeradorPlanilha.Indicador> indicadores = new GeradorPlanilha(modeloBytes).gerar(atual, anterior, saida);
        Path csv = null;
        if (detalhamento) {
            String nome = saida.getFileName().toString().replaceFirst("(?i)\\.xlsx$", "") + "_detalhamento.csv";
            csv = saida.toAbsolutePath().resolveSibling(nome);
            Detalhamento.escrever(csv, modelo, anterior == null ? List.of(atual) : List.of(atual, anterior));
        }

        imprimir(modelo, atual, anterior, indicadores, avisos);
        System.out.println();
        System.out.println("Planilha gerada: " + saida.toAbsolutePath());
        if (csv != null) System.out.println("Detalhamento por conta: " + csv);
        return 0;
    }

    // ------------------------------------------------------------------ saída no console

    private static void imprimir(ModeloBalanco modelo, BalancoPeriodo atual, BalancoPeriodo anterior,
                                 List<GeradorPlanilha.Indicador> indicadores, List<String> avisos) {
        Ecd ea = atual.ecd();
        System.out.println();
        System.out.println(ea.empresa() + " - CNPJ " + ea.cnpj());
        String cab = String.format("%-11s %-58s %18s %18s", "Código", "Conta", ea.fim().format(DATA),
                anterior == null ? "" : anterior.ecd().fim().format(DATA));
        System.out.println(cab);
        System.out.println("-".repeat(cab.length()));
        for (Grupo g : Grupo.values()) {
            for (LinhaModelo l : modelo.detalhesDo(g)) {
                System.out.printf("%-11s %-58s %18s %18s%n", l.codigo(), corta(l.descricao(), 58),
                        moeda(atual.valores().get(l.codigo())),
                        anterior == null ? "" : moeda(anterior.valores().get(l.codigo())));
            }
            System.out.printf("%-11s %-58s %18s %18s%n", "", "Total " + g.descricao(), moeda(atual.total(g)),
                    anterior == null ? "" : moeda(anterior.total(g)));
            if (g == Grupo.ANC) {
                System.out.printf("%-11s %-58s %18s %18s%n", "", "TOTAL DO ATIVO", moeda(atual.totalAtivo()),
                        anterior == null ? "" : moeda(anterior.totalAtivo()));
            }
        }
        System.out.printf("%-11s %-58s %18s %18s%n", "", "TOTAL DO PASSIVO E PATRIMÔNIO LÍQUIDO", moeda(atual.totalPassivoPl()),
                anterior == null ? "" : moeda(anterior.totalPassivoPl()));

        if (!indicadores.isEmpty()) {
            System.out.println();
            System.out.printf("%-45s %12s %12s   %s%n", "Indicador", ea.fim().getYear(),
                    anterior == null ? "" : String.valueOf(anterior.ecd().fim().getYear()), "Referência");
            for (var i : indicadores) {
                System.out.printf("%-45s %12s %12s   %s%n", corta(i.nome(), 45), indicador(i.nome(), i.atual()),
                        anterior == null ? "" : indicador(i.nome(), i.anterior()), i.referencia());
            }
        }

        // Conferências.
        List<BalancoPeriodo> periodos = anterior == null ? List.of(atual) : List.of(atual, anterior);
        for (BalancoPeriodo p : periodos) {
            String d = p.ecd().fim().format(DATA);
            BigDecimal dif = p.totalAtivo().subtract(p.totalPassivoPl());
            if (dif.abs().compareTo(TOLERANCIA) > 0) {
                avisos.add(d + ": balanço NÃO fecha. Ativo - (Passivo + PL) = " + moeda(dif));
            }
            Map<Grupo, BigDecimal> j100 = p.totaisJ100();
            if (!j100.isEmpty()) {
                for (Grupo g : Grupo.values()) {
                    BigDecimal entregue = j100.getOrDefault(g, BigDecimal.ZERO);
                    BigDecimal diferenca = p.total(g).subtract(entregue);
                    if (diferenca.abs().compareTo(TOLERANCIA) > 0) {
                        avisos.add(d + ": " + g.descricao() + " = " + moeda(p.total(g)) + ", no balanço entregue no SPED (J100) = "
                                + moeda(entregue) + " (diferença " + moeda(diferenca) + "; veja o CSV de detalhamento)");
                    }
                }
            }
            if (!p.classificacao().semGrupo().isEmpty()) {
                avisos.add(d + ": " + p.classificacao().semGrupo().size() + " conta(s) com saldo sem grupo identificado, fora do balanço: "
                        + p.classificacao().semGrupo().stream().limit(10).map(c -> c.codigo() + " " + c.nome()).toList());
            }
            if (p.classificacao().saldoCompensacaoExcluido().signum() != 0) {
                avisos.add(d + ": contas de compensação (natureza 05/09) ficaram fora do balanço, saldo líquido "
                        + moeda(p.classificacao().saldoCompensacaoExcluido()));
            }
            long resultado = p.enquadramentos().stream().filter(e -> e.conta().origemGrupo().startsWith("resultado")).count();
            if (resultado > 0) {
                BigDecimal v = p.enquadramentos().stream().filter(e -> e.conta().origemGrupo().startsWith("resultado"))
                        .map(e -> e.conta().valorNoBalanco()).reduce(BigDecimal.ZERO, BigDecimal::add);
                avisos.add(d + ": " + resultado + " conta(s) de resultado com saldo (exercício não encerrado no SPED); "
                        + moeda(v) + " somado em Lucros/Prejuízos Acumulados");
            }
        }
        if (anterior != null) {
            Map<Grupo, BigDecimal> ini = atual.totaisJ100Iniciais();
            for (Grupo g : Grupo.values()) {
                BigDecimal a = ini.get(g), f = anterior.totaisJ100().get(g);
                if (a != null && f != null && a.subtract(f).abs().compareTo(TOLERANCIA) > 0
                        && anterior.ecd().fim().plusDays(1).equals(atual.ecd().inicio())) {
                    avisos.add("Saldo inicial de " + g.descricao() + " no SPED de " + atual.ecd().fim().getYear() + " (" + moeda(a)
                            + ") difere do saldo final do SPED anterior (" + moeda(f) + "). Os arquivos podem ser de versões diferentes da escrituração.");
                }
            }
        }

        if (!avisos.isEmpty()) {
            System.out.println();
            System.out.println("Avisos:");
            avisos.forEach(a -> System.out.println("  - " + a));
        } else {
            System.out.println();
            System.out.println("Conferência: balanço fechado e totais iguais aos do balanço entregue no SPED.");
        }
    }

    private static String indicador(String nome, Double v) {
        if (v == null) return "-";
        String n = Texto.normalizar(nome);
        if (n.startsWith("CAPITAL CIRCULANTE")) return MOEDA.format(v);
        if (n.startsWith("ENDIVIDAMENTO") || n.startsWith("COMPOSICAO") || n.startsWith("PARTICIPACAO") || n.startsWith("IMOBILIZACAO")) {
            return new DecimalFormat("0.0%", DecimalFormatSymbols.getInstance(Locale.of("pt", "BR"))).format(v);
        }
        return new DecimalFormat("0.00", DecimalFormatSymbols.getInstance(Locale.of("pt", "BR"))).format(v);
    }

    private static String moeda(BigDecimal v) {
        return v == null ? "" : MOEDA.format(v.setScale(2, RoundingMode.HALF_UP));
    }

    private static String corta(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n - 1) + "…";
    }

    private static String limpo(String cnpj) {
        return cnpj == null ? "" : cnpj.replaceAll("\\D", "");
    }

    private static String valor(String[] args, int i, String opcao) {
        if (i >= args.length) throw new IllegalArgumentException("a opção " + opcao + " precisa de um valor");
        return args[i];
    }

    private static byte[] recurso(String nome) throws IOException {
        try (InputStream in = App.class.getResourceAsStream(nome)) {
            if (in == null) throw new IOException("recurso não encontrado no jar: " + nome);
            return in.readAllBytes();
        }
    }

    private static void uso() {
        System.out.println("""
                Uso: java -jar balanco-sped-cpc.jar ECD_1.txt [ECD_2.txt] [opções]

                Gera o Balanço Patrimonial no modelo CPC 26 (com os indicadores) a partir de arquivos SPED ECD.
                O arquivo de data-base mais recente vira o "Exercício Atual"; o outro, o "Exercício Anterior".

                Opções:
                  --saida arquivo.xlsx      planilha gerada (padrão: Balanco_CPC_<cnpj>_<data>.xlsx)
                  --modelo modelo.xlsx      modelo a preencher (padrão: o modelo embutido)
                  --mapeamento regras.csv   regras de enquadramento das contas (padrão: as embutidas)
                  --charset ISO-8859-1      codificação dos arquivos SPED (padrão: ISO-8859-1)
                  --sem-detalhamento        não gera o CSV de conferência por conta
                """);
    }
}
