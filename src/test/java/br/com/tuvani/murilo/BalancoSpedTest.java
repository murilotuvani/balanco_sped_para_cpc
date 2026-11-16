package br.com.tuvani.murilo;

import br.com.tuvani.murilo.mapeamento.Mapeador;
import br.com.tuvani.murilo.modelo.Grupo;
import br.com.tuvani.murilo.modelo.ModeloBalanco;
import br.com.tuvani.murilo.relatorio.BalancoPeriodo;
import br.com.tuvani.murilo.relatorio.GeradorPlanilha;
import br.com.tuvani.murilo.sped.Ecd;
import br.com.tuvani.murilo.sped.LeitorEcd;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BalancoSpedTest {

    private static byte[] modeloBytes;
    private static ModeloBalanco modelo;
    private static Mapeador mapeador;

    @TempDir
    Path tmp;

    @BeforeAll
    static void carregar() throws Exception {
        try (InputStream in = BalancoSpedTest.class.getResourceAsStream("/modelo/Modelo_Balanco_Patrimonial.xlsx")) {
            modeloBytes = in.readAllBytes();
        }
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(modeloBytes))) {
            modelo = ModeloBalanco.analisar(GeradorPlanilha.abaBalanco(wb));
        }
        try (var r = new InputStreamReader(BalancoSpedTest.class.getResourceAsStream("/mapeamento-padrao.csv"), StandardCharsets.UTF_8)) {
            mapeador = Mapeador.carregar(r, "mapeamento-padrao.csv", modelo);
        }
    }

    /** SPED mínimo: caixa, estoque, imobilizado e depreciação, fornecedores, empréstimo LP, capital e resultado aberto. */
    private Path sped(String nome, String dtIni, String dtFin, String caixa, String lucro) throws Exception {
        List<String> l = new ArrayList<>();
        l.add("|0000|LECD|" + dtIni + "|" + dtFin + "|EMPRESA TESTE LTDA|11222333000181|SP||||||||||||||||");
        l.add("|I010|G|9.00|");
        // plano de contas
        l.add("|I050|01012020|01|S|1|1||ATIVO|");
        l.add("|I050|01012020|01|S|2|11|1|ATIVO CIRCULANTE|");
        l.add("|I050|01012020|01|A|3|111|11|CAIXA GERAL|");
        l.add("|I051||1.01.01.01.01|");
        l.add("|I052||D111|");
        l.add("|I050|01012020|01|A|3|112|11|MERCADORIAS PARA REVENDA|");
        l.add("|I051||1.01.03.01.01|");
        l.add("|I052||D112|");
        l.add("|I050|01012020|01|S|2|12|1|ATIVO NAO CIRCULANTE|");
        l.add("|I050|01012020|01|A|3|121|12|VEÍCULOS|");
        l.add("|I051||1.02.03.01.08|");
        l.add("|I052||D121|");
        l.add("|I050|01012020|01|A|3|122|12|(-) DEPRECIAÇÃO DE VEÍCULOS|");
        l.add("|I051||1.02.03.01.30|");
        l.add("|I052||D122|");
        l.add("|I050|01012020|02|S|1|2||PASSIVO|");
        l.add("|I050|01012020|02|A|2|211|2|FORNECEDOR X|");
        l.add("|I051||2.01.01.03.01|");
        l.add("|I052||D211|");
        l.add("|I050|01012020|02|A|2|221|2|EMPRESTIMO BANCO Y|");
        l.add("|I051||2.02.01.01.06|");
        l.add("|I052||D221|");
        l.add("|I050|01012020|03|A|2|231|2|CAPITAL SOCIAL REALIZADO|");
        l.add("|I051||2.03.01.01.01|");
        l.add("|I052||D231|");
        l.add("|I050|01012020|04|A|1|311||VENDAS|");
        l.add("|I051||3.01.01.01.01.01|");
        // saldos: um período antes (deve ser ignorado) e o do fim
        l.add("|I150|" + dtIni + "|" + dtIni + "|");
        l.add("|I155|111||0|D|0|0|999999,00|D|");
        l.add("|I150|" + dtIni + "|" + dtFin + "|");
        BigDecimal cx = new BigDecimal(caixa);
        BigDecimal lc = new BigDecimal(lucro);
        // ativo = caixa + 500 + 1000 - 200 ; passivo = 300 + 400 + capital ; resultado credor = lucro
        BigDecimal capital = cx.add(new BigDecimal("1300")).subtract(new BigDecimal("700")).subtract(lc);
        l.add("|I155|111||0|D|0|0|" + cx.toPlainString().replace('.', ',') + "|D|");
        l.add("|I155|112||0|D|0|0|500,00|D|");
        l.add("|I155|121||0|D|0|0|1000,00|D|");
        l.add("|I155|122||0|D|0|0|200,00|C|");
        l.add("|I155|211||0|D|0|0|300,00|C|");
        l.add("|I155|221||0|D|0|0|400,00|C|");
        l.add("|I155|231||0|D|0|0|" + capital.toPlainString().replace('.', ',') + "|C|");
        l.add("|I155|311||0|D|0|0|" + lc.toPlainString().replace('.', ',') + "|C|");
        l.add("|I200|1|01012025|10,00|N||");
        l.add("garbage line that is not a record");
        // balanço entregue
        l.add("|J005|" + dtIni + "|" + dtFin + "|1||");
        l.add("|J100|A1|T|1||A|ATIVO|0|D|0|D||");
        l.add("|J100|AC|T|2|A1|A|ATIVO CIRCULANTE|0|D|0|D||");
        l.add("|J100|DISP|T|3|AC|A|DISPONÍVEL|0|D|0|D||");
        l.add("|J100|D111|D|4|DISP|A|CAIXA GERAL|0|D|0|D||");
        l.add("|J100|EST|T|3|AC|A|ESTOQUE|0|D|0|D||");
        l.add("|J100|D112|D|4|EST|A|MERCADORIAS|0|D|0|D||");
        l.add("|J100|ANC|T|2|A1|A|ATIVO NÃO-CIRCULANTE|0|D|0|D||");
        l.add("|J100|IMOB|T|3|ANC|A|IMOBILIZADO|0|D|0|D||");
        l.add("|J100|D121|D|4|IMOB|A|VEICULOS|0|D|0|D||");
        l.add("|J100|DEP|T|4|IMOB|A|(-) DEPRECIAÇÕES ACUMULADAS|0|D|0|D||");
        l.add("|J100|D122|D|5|DEP|A|(-) DEPREC VEICULOS|0|D|0|D||");
        l.add("|J100|P1|T|1||P|PASSIVO|0|C|0|C||");
        l.add("|J100|PC|T|2|P1|P|PASSIVO CIRCULANTE|0|C|0|C||");
        l.add("|J100|FORN|T|3|PC|P|FORNECEDORES|0|C|0|C||");
        l.add("|J100|D211|D|4|FORN|P|FORNECEDOR X|0|C|0|C||");
        l.add("|J100|PNC|T|2|P1|P|PASSIVO NÃO-CIRCULANTE|0|C|0|C||");
        l.add("|J100|D221|D|3|PNC|P|EMPRESTIMO|0|C|0|C||");
        l.add("|J100|PL|T|2|P1|P|PATRIMÔNIO LÍQUIDO|0|C|0|C||");
        l.add("|J100|CAP|T|3|PL|P|CAPITAL SOCIAL|0|C|0|C||");
        l.add("|J100|D231|D|4|CAP|P|CAPITAL|0|C|0|C||");
        l.add("|9999|80|");
        Path p = tmp.resolve(nome);
        Files.write(p, l, StandardCharsets.ISO_8859_1);
        return p;
    }

    @Test
    void montaBalancoNasLinhasDoModelo() throws Exception {
        Ecd ecd = new LeitorEcd(StandardCharsets.ISO_8859_1).ler(sped("a.txt", "01012025", "31122025", "100.50", "250"));
        assertEquals("EMPRESA TESTE LTDA", ecd.empresa());
        BalancoPeriodo b = BalancoPeriodo.montar(ecd, modelo, mapeador);

        assertEquals(0, new BigDecimal("100.50").compareTo(b.valores().get("1.1.01")));
        assertEquals(0, new BigDecimal("500").compareTo(b.valores().get("1.1.05")));
        assertEquals(0, new BigDecimal("1000").compareTo(b.valores().get("1.2.03.02")));
        assertEquals(0, new BigDecimal("-200").compareTo(b.valores().get("1.2.03.04")));
        assertEquals(0, new BigDecimal("300").compareTo(b.valores().get("2.1.01")));
        assertEquals(0, new BigDecimal("400").compareTo(b.valores().get("2.2.01")));
        // resultado não encerrado vai para lucros/prejuízos acumulados
        assertEquals(0, new BigDecimal("250").compareTo(b.valores().get("2.3.05")));
        assertEquals(0, b.totalAtivo().compareTo(b.totalPassivoPl()));
        assertEquals(0, new BigDecimal("600.50").compareTo(b.total(Grupo.AC)));
    }

    @Test
    void planilhaTemAsMesmasLinhasDoModeloEFecha() throws Exception {
        Ecd atual = new LeitorEcd(StandardCharsets.ISO_8859_1).ler(sped("a.txt", "01012025", "31122025", "100", "250"));
        Ecd ant = new LeitorEcd(StandardCharsets.ISO_8859_1).ler(sped("b.txt", "01012024", "31122024", "80", "0"));
        Path saida = tmp.resolve("saida.xlsx");
        var indicadores = new GeradorPlanilha(modeloBytes).gerar(
                BalancoPeriodo.montar(atual, modelo, mapeador), BalancoPeriodo.montar(ant, modelo, mapeador), saida);

        try (XSSFWorkbook orig = new XSSFWorkbook(new ByteArrayInputStream(modeloBytes));
             XSSFWorkbook ger = new XSSFWorkbook(Files.newInputStream(saida))) {
            assertEquals(orig.getNumberOfSheets(), ger.getNumberOfSheets());
            Sheet so = GeradorPlanilha.abaBalanco(orig), sg = GeradorPlanilha.abaBalanco(ger);
            assertEquals(so.getLastRowNum(), sg.getLastRowNum(), "não pode criar linhas novas");
            for (int r = 0; r <= so.getLastRowNum(); r++) {
                Row ro = so.getRow(r), rg = sg.getRow(r);
                String co = ro == null || ro.getCell(1) == null ? "" : ro.getCell(1).toString();
                String cg = rg == null || rg.getCell(1) == null ? "" : rg.getCell(1).toString();
                if (!co.contains("CNPJ")) assertEquals(co, cg, "código da linha " + (r + 1));
            }
            var ev = ger.getCreationHelper().createFormulaEvaluator();
            Cell prova = sg.getRow(modelo.linhaProvaReal()).getCell(modelo.colAvAtual());
            assertEquals("FECHADO", ev.evaluate(prova).getStringValue());
            Cell cab = sg.getRow(modelo.linhaCabecalho()).getCell(modelo.colAtual());
            assertTrue(cab.getStringCellValue().contains("2025"));
        }
        var lc = indicadores.stream().filter(i -> i.nome().startsWith("Liquidez Corrente")).findFirst().orElseThrow();
        assertEquals(600d / 300d, lc.atual(), 1e-9);
    }

    @Test
    void regraParaLinhaInexistenteEhRecusada() {
        String csv = "AC;*;.*;1.1.99;linha que não existe\n";
        var e = assertThrows(IllegalArgumentException.class,
                () -> Mapeador.carregar(new java.io.StringReader(csv), "t.csv", modelo));
        assertTrue(e.getMessage().contains("1.1.99"));
    }
}
