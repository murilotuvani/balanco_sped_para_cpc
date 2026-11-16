package br.com.tuvani.murilo.relatorio;

import br.com.tuvani.murilo.mapeamento.Enquadramento;
import br.com.tuvani.murilo.modelo.LinhaModelo;
import br.com.tuvani.murilo.modelo.ModeloBalanco;

import java.io.BufferedWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Gera um CSV (separador ";", vírgula decimal, UTF-8 com BOM para abrir direto no Excel) com cada
 * conta analítica, a linha do modelo em que entrou e a regra que decidiu. Serve para conferir o
 * balanço sem acrescentar nada ao relatório.
 */
public final class Detalhamento {

    private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private Detalhamento() {
    }

    public static void escrever(Path arquivo, ModeloBalanco modelo, List<BalancoPeriodo> periodos) throws IOException {
        try (BufferedWriter w = Files.newBufferedWriter(arquivo, StandardCharsets.UTF_8)) {
            w.write('﻿');
            w.write("data_base;linha_modelo;descricao_linha;grupo;origem_grupo;conta;descricao_conta;referencial;"
                    + "aglutinacao_j100;saldo_contabil;valor_no_balanco;regra;comentario_regra\n");
            for (BalancoPeriodo p : periodos) {
                String data = p.ecd().fim().format(DATA);
                for (Enquadramento e : p.enquadramentos()) {
                    LinhaModelo l = modelo.porCodigo().get(e.linha());
                    var c = e.conta();
                    w.write(String.join(";",
                            data, e.linha(), csv(l.descricao()), c.grupo().name(), csv(c.origemGrupo()),
                            csv(c.conta().codigo()), csv(c.conta().nome()), csv(String.join(" ", c.conta().referenciais())),
                            csv(c.caminhoAglutinacao()), num(c.saldo()), num(c.valorNoBalanco()),
                            csv(e.regra().origem()), csv(e.regra().comentario())));
                    w.write('\n');
                }
            }
        }
    }

    private static String csv(String s) {
        if (s == null) return "";
        if (s.contains(";") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    private static String num(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP).toPlainString().replace('.', ',');
    }
}
