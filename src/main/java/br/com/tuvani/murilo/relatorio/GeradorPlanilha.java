package br.com.tuvani.murilo.relatorio;

import br.com.tuvani.murilo.Texto;
import br.com.tuvani.murilo.modelo.Grupo;
import br.com.tuvani.murilo.modelo.LinhaModelo;
import br.com.tuvani.murilo.modelo.ModeloBalanco;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.CellValue;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFChart;
import org.apache.poi.xssf.usermodel.XSSFDrawing;
import org.apache.poi.xssf.usermodel.XSSFRichTextString;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTCell;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.STCellType;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Preenche o modelo de balanço com os valores apurados, mantendo exatamente as linhas, níveis,
 * formatação e gráficos do modelo. Só escreve valores nas linhas de detalhe e (re)escreve as
 * fórmulas de totais, AV%, AH%, prova real e indicadores apontando para as linhas certas.
 */
public final class GeradorPlanilha {

    /** Indicador calculado, lido da aba de indicadores depois da avaliação das fórmulas. */
    public record Indicador(String categoria, String nome, Double atual, Double anterior, String referencia) {
    }

    private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    /** Anos usados como rótulo no modelo fornecido: 2026 = exercício atual, 2025 = anterior. */
    private static final Pattern ANOS_MODELO = Pattern.compile("\\b(2026|2025)\\b");

    private final byte[] modeloXlsx;

    public GeradorPlanilha(byte[] modeloXlsx) {
        this.modeloXlsx = modeloXlsx;
    }

    public static Sheet abaBalanco(XSSFWorkbook wb) {
        for (Sheet s : wb) {
            if (Texto.normalizar(s.getSheetName()).contains("BALANCO")) return s;
        }
        return wb.getSheetAt(0);
    }

    private static Sheet abaIndicadores(XSSFWorkbook wb) {
        for (Sheet s : wb) {
            if (Texto.normalizar(s.getSheetName()).contains("INDICADORES")) return s;
        }
        return null;
    }

    public List<Indicador> gerar(BalancoPeriodo atual, BalancoPeriodo anterior, Path saida) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(modeloXlsx))) {
            converterStringsEmLinha(wb);
            Sheet bp = abaBalanco(wb);
            ModeloBalanco m = ModeloBalanco.analisar(bp);

            preencherValores(bp, m, atual, anterior);
            escreverFormulasBalanco(bp, m);
            String rotAtual = rotulo(atual, anterior);
            String rotAnterior = anterior == null ? "-" : rotulo(anterior, atual);
            substituirAnos(wb, rotAtual, rotAnterior);
            escreverCabecalhoEmpresa(bp, m, atual, anterior);

            Sheet ind = abaIndicadores(wb);
            if (ind != null) {
                escreverFormulasIndicadores(ind, bp, m);
            }

            FormulaEvaluator ev = wb.getCreationHelper().createFormulaEvaluator();
            ev.evaluateAll();
            wb.setForceFormulaRecalculation(true);

            List<Indicador> indicadores = ind == null ? List.of() : lerIndicadores(ind, ev);

            Path pasta = saida.toAbsolutePath().getParent();
            if (pasta != null) Files.createDirectories(pasta);
            try (OutputStream out = Files.newOutputStream(saida)) {
                wb.write(out);
            }
            return indicadores;
        }
    }

    /**
     * O modelo grava textos como "inline strings"; o POI não consegue alterá-los corretamente
     * (mantém o texto antigo). Converte todos para a tabela de strings compartilhadas.
     */
    private static void converterStringsEmLinha(XSSFWorkbook wb) {
        for (Sheet s : wb) {
            for (Row row : s) {
                for (Cell c : row) {
                    CTCell ct = ((XSSFCell) c).getCTCell();
                    if (ct.getT() == STCellType.INLINE_STR) {
                        String v = c.getStringCellValue();
                        if (ct.isSetIs()) ct.unsetIs();
                        if (ct.isSetV()) ct.unsetV();
                        ct.setT(STCellType.S);
                        c.setCellValue(v);
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ valores

    private static void preencherValores(Sheet bp, ModeloBalanco m, BalancoPeriodo atual, BalancoPeriodo anterior) {
        for (LinhaModelo l : m.detalhes()) {
            Row row = linha(bp, l.linha());
            celula(row, m.colAtual()).setCellValue(valor(atual, l.codigo()));
            celula(row, m.colAnterior()).setCellValue(anterior == null ? 0d : valor(anterior, l.codigo()));
        }
    }

    private static double valor(BalancoPeriodo b, String codigo) {
        BigDecimal v = b.valores().getOrDefault(codigo, BigDecimal.ZERO);
        return v.setScale(2, java.math.RoundingMode.HALF_UP).doubleValue();
    }

    // ------------------------------------------------------------------ fórmulas do balanço

    private static void escreverFormulasBalanco(Sheet bp, ModeloBalanco m) {
        String cA = col(m.colAtual()), cB = col(m.colAnterior());
        int tAtivo = m.linhaTotalAtivo(), tPassivo = m.linhaTotalPassivoPl();

        for (Grupo g : Grupo.values()) {
            List<Integer> linhas = m.detalhesDo(g).stream().map(LinhaModelo::linha).toList();
            Row row = linha(bp, m.linhaTotal(g));
            celula(row, m.colAtual()).setCellFormula(soma(cA, linhas));
            celula(row, m.colAnterior()).setCellFormula(soma(cB, linhas));
        }
        Row ra = linha(bp, tAtivo);
        celula(ra, m.colAtual()).setCellFormula(cA + (m.linhaTotal(Grupo.AC) + 1) + "+" + cA + (m.linhaTotal(Grupo.ANC) + 1));
        celula(ra, m.colAnterior()).setCellFormula(cB + (m.linhaTotal(Grupo.AC) + 1) + "+" + cB + (m.linhaTotal(Grupo.ANC) + 1));
        Row rp = linha(bp, tPassivo);
        celula(rp, m.colAtual()).setCellFormula(cA + (m.linhaTotal(Grupo.PC) + 1) + "+" + cA + (m.linhaTotal(Grupo.PNC) + 1) + "+" + cA + (m.linhaTotal(Grupo.PL) + 1));
        celula(rp, m.colAnterior()).setCellFormula(cB + (m.linhaTotal(Grupo.PC) + 1) + "+" + cB + (m.linhaTotal(Grupo.PNC) + 1) + "+" + cB + (m.linhaTotal(Grupo.PL) + 1));

        // AV% e AH% em todas as linhas com valor (detalhes e totais).
        List<Integer> comValor = new ArrayList<>();
        m.detalhes().forEach(d -> comValor.add(d.linha()));
        for (Grupo g : Grupo.values()) comValor.add(m.linhaTotal(g));
        comValor.add(tAtivo);
        comValor.add(tPassivo);
        for (int r : comValor) {
            int base = r <= tAtivo ? tAtivo : tPassivo;
            Row row = linha(bp, r);
            int n = r + 1, b = base + 1;
            celula(row, m.colAvAtual()).setCellFormula("IFERROR(" + cA + n + "/$" + cA + "$" + b + ", 0)");
            celula(row, m.colAvAnterior()).setCellFormula("IFERROR(" + cB + n + "/$" + cB + "$" + b + ", 0)");
            celula(row, m.colHorizontal()).setCellFormula("IFERROR((" + cA + n + "-" + cB + n + ")/ABS(" + cB + n + "), 0)");
        }

        if (m.linhaProvaReal() >= 0) {
            Row row = linha(bp, m.linhaProvaReal());
            int n = m.linhaProvaReal() + 1;
            celula(row, m.colAtual()).setCellFormula("ROUND(" + cA + (tAtivo + 1) + "-" + cA + (tPassivo + 1) + ", 2)");
            celula(row, m.colAvAtual()).setCellFormula("IF(" + cA + n + "=0, \"FECHADO\", \"DIFERENÇA\")");
            celula(row, m.colAnterior()).setCellFormula("ROUND(" + cB + (tAtivo + 1) + "-" + cB + (tPassivo + 1) + ", 2)");
            celula(row, m.colAvAnterior()).setCellFormula("IF(" + cB + n + "=0, \"FECHADO\", \"DIFERENÇA\")");
        }
    }

    /** SUM com intervalos contíguos agrupados: SUM(D8:D14) ou SUM(D18:D19,D21,...). */
    static String soma(String col, List<Integer> linhas) {
        if (linhas.isEmpty()) return "0";
        List<String> partes = new ArrayList<>();
        int ini = linhas.get(0), ant = ini;
        for (int i = 1; i <= linhas.size(); i++) {
            int atual = i < linhas.size() ? linhas.get(i) : Integer.MIN_VALUE;
            if (atual != ant + 1) {
                partes.add(ini == ant ? col + (ini + 1) : col + (ini + 1) + ":" + col + (ant + 1));
                ini = atual;
            }
            ant = atual;
        }
        return "SUM(" + String.join(",", partes) + ")";
    }

    // ------------------------------------------------------------------ textos

    private static String rotulo(BalancoPeriodo p, BalancoPeriodo outro) {
        boolean mesmoAno = outro != null && outro.ecd().fim().getYear() == p.ecd().fim().getYear();
        return mesmoAno ? p.ecd().fim().format(DATA) : String.valueOf(p.ecd().fim().getYear());
    }

    /** Troca os anos-rótulo do modelo (2026/2025) pelos anos reais, em todas as abas e gráficos. */
    private static void substituirAnos(XSSFWorkbook wb, String atual, String anterior) {
        Function<String, String> troca = s -> {
            Matcher mt = ANOS_MODELO.matcher(s);
            StringBuilder sb = new StringBuilder();
            while (mt.find()) {
                mt.appendReplacement(sb, Matcher.quoteReplacement(mt.group(1).equals("2026") ? atual : anterior));
            }
            mt.appendTail(sb);
            return sb.toString();
        };
        for (Sheet s : wb) {
            for (Row row : s) {
                for (Cell c : row) {
                    if (c.getCellType() == CellType.STRING) {
                        String v = c.getStringCellValue();
                        String n = troca.apply(v);
                        if (!n.equals(v)) c.setCellValue(n);
                    }
                }
            }
            XSSFDrawing d = ((XSSFSheet) s).getDrawingPatriarch();
            if (d != null) {
                for (XSSFChart ch : d.getCharts()) {
                    XSSFRichTextString t = ch.getTitleText();
                    if (t != null && t.getString() != null) {
                        String n = troca.apply(t.getString());
                        if (!n.equals(t.getString())) ch.setTitleText(n);
                    }
                }
            }
        }
    }

    private static void escreverCabecalhoEmpresa(Sheet bp, ModeloBalanco m, BalancoPeriodo atual, BalancoPeriodo anterior) {
        String datas = atual.ecd().fim().format(DATA) + (anterior == null ? "" : " e " + anterior.ecd().fim().format(DATA));
        String texto = atual.ecd().empresa() + "  |  CNPJ: " + cnpj(atual.ecd().cnpj())
                + "  |  Valores em Reais (R$)  |  Fonte: SPED ECD, saldos em " + datas;
        for (int r = 0; r < m.linhaCabecalho(); r++) {
            Row row = bp.getRow(r);
            if (row == null) continue;
            for (Cell c : row) {
                if (c.getCellType() == CellType.STRING && Texto.normalizar(c.getStringCellValue()).contains("CNPJ")) {
                    c.setCellValue(texto);
                    return;
                }
            }
        }
    }

    static String cnpj(String c) {
        if (c == null || !c.matches("\\d{14}")) return c;
        return c.substring(0, 2) + "." + c.substring(2, 5) + "." + c.substring(5, 8) + "/" + c.substring(8, 12) + "-" + c.substring(12);
    }

    // ------------------------------------------------------------------ indicadores

    /** Referências às células do balanço usadas pelos indicadores, para uma coluna (atual ou anterior). */
    private record Refs(String ac, String anc, String pc, String pnc, String pl, String at, String ppl,
                        String est, String disp, String rlp) {
    }

    private static Refs refs(Sheet bp, ModeloBalanco m, int coluna) {
        String aba = "'" + bp.getSheetName().replace("'", "''") + "'!";
        String c = col(coluna);
        Function<Integer, String> ref = r -> aba + c + (r + 1);
        Function<List<LinhaModelo>, String> somaDe = ls -> ls.isEmpty() ? "0"
                : "(" + String.join("+", ls.stream().map(l -> ref.apply(l.linha())).toList()) + ")";
        return new Refs(
                ref.apply(m.linhaTotal(Grupo.AC)), ref.apply(m.linhaTotal(Grupo.ANC)),
                ref.apply(m.linhaTotal(Grupo.PC)), ref.apply(m.linhaTotal(Grupo.PNC)),
                ref.apply(m.linhaTotal(Grupo.PL)), ref.apply(m.linhaTotalAtivo()), ref.apply(m.linhaTotalPassivoPl()),
                somaDe.apply(m.detalhesPorDescricao(Grupo.AC, "ESTOQUE")),
                somaDe.apply(m.detalhesPorDescricao(Grupo.AC, "CAIXA|LIQUIDEZ IMEDIATA|DISPONI")),
                somaDe.apply(m.detalhesAbaixoDe("REALIZAVEL A LONGO PRAZO")));
    }

    private static final Map<String, Function<Refs, String>> FORMULAS = new LinkedHashMap<>();

    static {
        FORMULAS.put("LIQUIDEZ CORRENTE", r -> "IFERROR(" + r.ac + "/" + r.pc + ", 0)");
        FORMULAS.put("LIQUIDEZ SECA", r -> "IFERROR((" + r.ac + "-" + r.est + ")/" + r.pc + ", 0)");
        FORMULAS.put("LIQUIDEZ IMEDIATA", r -> "IFERROR(" + r.disp + "/" + r.pc + ", 0)");
        FORMULAS.put("LIQUIDEZ GERAL", r -> "IFERROR((" + r.ac + "+" + r.rlp + ")/(" + r.pc + "+" + r.pnc + "), 0)");
        FORMULAS.put("CAPITAL CIRCULANTE LIQUIDO", r -> r.ac + "-" + r.pc);
        FORMULAS.put("ENDIVIDAMENTO GERAL", r -> "IFERROR((" + r.pc + "+" + r.pnc + ")/" + r.at + ", 0)");
        FORMULAS.put("COMPOSICAO DO ENDIVIDAMENTO", r -> "IFERROR(" + r.pc + "/(" + r.pc + "+" + r.pnc + "), 0)");
        FORMULAS.put("PARTICIPACAO DE CAPITAIS DE TERCEIROS", r -> "IFERROR((" + r.pc + "+" + r.pnc + ")/" + r.pl + ", 0)");
        FORMULAS.put("IMOBILIZACAO DO PATRIMONIO LIQUIDO", r -> "IFERROR(" + r.anc + "/" + r.pl + ", 0)");
    }

    private static Function<Refs, String> formulaDoIndicador(String rotulo) {
        String n = Texto.normalizar(rotulo);
        for (var e : FORMULAS.entrySet()) {
            if (n.startsWith(e.getKey())) return e.getValue();
        }
        return null;
    }

    private static void escreverFormulasIndicadores(Sheet ind, Sheet bp, ModeloBalanco m) {
        Refs atual = refs(bp, m, m.colAtual());
        Refs ant = refs(bp, m, m.colAnterior());

        for (Row row : ind) {
            // Cartões do topo: rótulo numa linha, valor na linha de baixo, mesma coluna.
            for (Cell c : row) {
                if (c.getCellType() != CellType.STRING) continue;
                String t = Texto.normalizar(c.getStringCellValue());
                String f = null;
                if (t.startsWith("TOTAL DO ATIVO")) f = atual.at;
                else if (t.startsWith("PATRIMONIO LIQUIDO (")) f = atual.pl;
                else if (t.startsWith("CAPITAL CIRCULANTE LIQUIDO") && c.getColumnIndex() != 2) f = atual.ac + "-" + atual.pc;
                else if (t.startsWith("LIQUIDEZ CORRENTE (")) f = "IFERROR(" + atual.ac + "/" + atual.pc + ", 0)";
                if (f != null) {
                    Row abaixo = linha(ind, row.getRowNum() + 1);
                    Cell alvo = abaixo.getCell(c.getColumnIndex());
                    if (alvo != null && alvo.getCellType() == CellType.FORMULA) alvo.setCellFormula(f);
                }
            }

            // Tabela de indicadores: rótulo na coluna C, atual em D, anterior em E.
            Cell rotulo = row.getCell(2);
            if (rotulo != null && rotulo.getCellType() == CellType.STRING) {
                Function<Refs, String> f = formulaDoIndicador(rotulo.getStringCellValue());
                Cell d = row.getCell(3), e = row.getCell(4);
                if (f != null && d != null && d.getCellType() == CellType.FORMULA) {
                    d.setCellFormula(f.apply(atual));
                    if (e != null) e.setCellFormula(f.apply(ant));
                }
            }

            // Composição patrimonial: rótulo na coluna B, anterior em C, atual em D, participação em E.
            Cell grupo = row.getCell(1);
            if (grupo != null && grupo.getCellType() == CellType.STRING) {
                Cell cAnt = row.getCell(2), cAt = row.getCell(3), part = row.getCell(4);
                if (cAnt == null || cAnt.getCellType() != CellType.FORMULA) continue;
                String g = Texto.normalizar(grupo.getStringCellValue());
                String[] par = switch (g) {
                    case "ATIVO CIRCULANTE" -> new String[]{ant.ac, atual.ac, atual.at};
                    case "ATIVO NAO CIRCULANTE" -> new String[]{ant.anc, atual.anc, atual.at};
                    case "PASSIVO CIRCULANTE" -> new String[]{ant.pc, atual.pc, atual.ppl};
                    case "PASSIVO NAO CIRCULANTE" -> new String[]{ant.pnc, atual.pnc, atual.ppl};
                    case "PATRIMONIO LIQUIDO" -> new String[]{ant.pl, atual.pl, atual.ppl};
                    default -> null;
                };
                if (par == null) continue;
                cAnt.setCellFormula(par[0]);
                if (cAt != null) cAt.setCellFormula(par[1]);
                if (part != null) {
                    String self = col(3) + (row.getRowNum() + 1);
                    part.setCellFormula("IFERROR(" + self + "/" + par[2] + ", 0)");
                }
            }
        }
    }

    private static List<Indicador> lerIndicadores(Sheet ind, FormulaEvaluator ev) {
        List<Indicador> lista = new ArrayList<>();
        for (Row row : ind) {
            Cell rotulo = row.getCell(2);
            if (rotulo == null || rotulo.getCellType() != CellType.STRING) continue;
            if (formulaDoIndicador(rotulo.getStringCellValue()) == null) continue;
            lista.add(new Indicador(texto(row.getCell(1)), rotulo.getStringCellValue(),
                    numero(row.getCell(3), ev), numero(row.getCell(4), ev), texto(row.getCell(6))));
        }
        return lista;
    }

    private static Double numero(Cell c, FormulaEvaluator ev) {
        if (c == null) return null;
        CellValue v = ev.evaluate(c);
        return v != null && v.getCellType() == CellType.NUMERIC ? v.getNumberValue() : null;
    }

    private static String texto(Cell c) {
        return c != null && c.getCellType() == CellType.STRING ? c.getStringCellValue() : "";
    }

    // ------------------------------------------------------------------ util

    private static String col(int indice) {
        return CellReference.convertNumToColString(indice);
    }

    private static Row linha(Sheet s, int r) {
        Row row = s.getRow(r);
        return row != null ? row : s.createRow(r);
    }

    private static Cell celula(Row row, int c) {
        Cell cell = row.getCell(c);
        return cell != null ? cell : row.createCell(c);
    }
}
