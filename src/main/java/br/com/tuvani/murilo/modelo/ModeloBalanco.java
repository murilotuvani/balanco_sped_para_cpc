package br.com.tuvani.murilo.modelo;

import br.com.tuvani.murilo.Texto;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Estrutura da aba de balanço do modelo, descoberta lendo a própria planilha: códigos, linhas de
 * detalhe (que recebem valores), linhas de total e colunas. Nada é criado além do que já existe
 * no modelo.
 */
public final class ModeloBalanco {

    private static final Pattern CODIGO = Pattern.compile("^\\d+(\\.\\d+)*$");

    private final List<LinhaModelo> detalhes;
    private final Map<String, String> descricoes;
    private final Map<String, LinhaModelo> porCodigo;
    private final Map<Grupo, Integer> linhaTotal;
    private final int linhaTotalAtivo;
    private final int linhaTotalPassivoPl;
    private final int linhaProvaReal;
    private final int linhaCabecalho;
    private final int colCodigo;
    private final int colDescricao;
    private final int colAtual;
    private final int colAvAtual;
    private final int colAnterior;
    private final int colAvAnterior;
    private final int colHorizontal;

    private ModeloBalanco(List<LinhaModelo> detalhes, Map<String, String> descricoes, Map<Grupo, Integer> linhaTotal, int linhaTotalAtivo,
                          int linhaTotalPassivoPl, int linhaProvaReal, int linhaCabecalho, int colCodigo,
                          int colDescricao, int colAtual, int colAvAtual, int colAnterior, int colAvAnterior,
                          int colHorizontal) {
        this.detalhes = List.copyOf(detalhes);
        this.descricoes = Collections.unmodifiableMap(new LinkedHashMap<>(descricoes));
        Map<String, LinhaModelo> m = new LinkedHashMap<>();
        detalhes.forEach(d -> m.put(d.codigo(), d));
        this.porCodigo = Collections.unmodifiableMap(m);
        this.linhaTotal = Collections.unmodifiableMap(linhaTotal);
        this.linhaTotalAtivo = linhaTotalAtivo;
        this.linhaTotalPassivoPl = linhaTotalPassivoPl;
        this.linhaProvaReal = linhaProvaReal;
        this.linhaCabecalho = linhaCabecalho;
        this.colCodigo = colCodigo;
        this.colDescricao = colDescricao;
        this.colAtual = colAtual;
        this.colAvAtual = colAvAtual;
        this.colAnterior = colAnterior;
        this.colAvAnterior = colAvAnterior;
        this.colHorizontal = colHorizontal;
    }

    public static ModeloBalanco analisar(Sheet aba) {
        // 1) Cabeçalho: linha com "Código" e "Exercício Atual".
        int linhaCab = -1;
        int colCodigo = 1, colDescricao = 2, colAtual = 3, colAvAtual = 4, colAnterior = 5, colAvAnterior = 6, colHorizontal = 7;
        for (Row row : aba) {
            Map<Integer, String> textos = textos(row);
            Integer cCodigo = null, cAtual = null, cAnterior = null, cHoriz = null, cConta = null;
            for (var e : textos.entrySet()) {
                String t = e.getValue();
                if (t.equals("CODIGO")) cCodigo = e.getKey();
                else if (t.startsWith("CONTA")) cConta = e.getKey();
                else if (t.startsWith("EXERCICIO ATUAL")) cAtual = e.getKey();
                else if (t.startsWith("EXERCICIO ANTERIOR")) cAnterior = e.getKey();
                else if (t.contains("HORIZONTAL")) cHoriz = e.getKey();
            }
            if (cCodigo != null && cAtual != null) {
                linhaCab = row.getRowNum();
                colCodigo = cCodigo;
                colDescricao = cConta != null ? cConta : cCodigo + 1;
                colAtual = cAtual;
                colAvAtual = cAtual + 1;
                colAnterior = cAnterior != null ? cAnterior : cAtual + 2;
                colAvAnterior = colAnterior + 1;
                colHorizontal = cHoriz != null ? cHoriz : colAnterior + 2;
                break;
            }
        }
        if (linhaCab < 0) {
            throw new IllegalArgumentException("Modelo sem cabeçalho 'Código' / 'Exercício Atual' na aba " + aba.getSheetName());
        }

        // 2) Códigos de conta na coluna "Código".
        record Item(int linha, String codigo, String descricao) {
        }
        List<Item> itens = new ArrayList<>();
        for (int r = linhaCab + 1; r <= aba.getLastRowNum(); r++) {
            Row row = aba.getRow(r);
            if (row == null) continue;
            String cod = texto(row.getCell(colCodigo)).trim();
            if (CODIGO.matcher(cod).matches()) {
                itens.add(new Item(r, cod, texto(row.getCell(colDescricao)).trim()));
            }
        }

        // 3) Detalhe = código sem filhos.
        List<LinhaModelo> detalhes = new ArrayList<>();
        for (Item it : itens) {
            boolean temFilho = itens.stream().anyMatch(o -> o.codigo().startsWith(it.codigo() + "."));
            Grupo g = Grupo.doCodigoLinha(it.codigo());
            if (!temFilho && g != null) {
                detalhes.add(new LinhaModelo(it.codigo(), it.descricao(), it.linha(), g));
            }
        }

        // 4) Linhas de total e prova real.
        Map<Grupo, Integer> totais = new EnumMap<>(Grupo.class);
        int totalAtivo = -1, totalPassivo = -1, provaReal = -1;
        Grupo grupoCorrente = null;
        for (int r = linhaCab + 1; r <= aba.getLastRowNum(); r++) {
            Row row = aba.getRow(r);
            if (row == null) continue;
            String cod = texto(row.getCell(colCodigo)).trim();
            String desc = Texto.normalizar(texto(row.getCell(colDescricao)));
            if (CODIGO.matcher(cod).matches()) {
                for (Grupo g : Grupo.values()) {
                    if (cod.equals(g.prefixo())) grupoCorrente = g;
                }
            } else if (Texto.normalizar(cod).startsWith("PROVA REAL")) {
                provaReal = r;
            } else if (cod.isEmpty() && desc.startsWith("TOTAL D")) {
                if (desc.contains("PASSIVO E PATRIMONIO")) {
                    totalPassivo = r;
                } else if (desc.matches("TOTAL DO ATIVO\\s*$")) {
                    totalAtivo = r;
                } else if (grupoCorrente != null) {
                    totais.put(grupoCorrente, r);
                }
            }
        }
        for (Grupo g : Grupo.values()) {
            if (!totais.containsKey(g)) {
                throw new IllegalArgumentException("Modelo sem linha de total para " + g.descricao());
            }
        }
        if (totalAtivo < 0 || totalPassivo < 0) {
            throw new IllegalArgumentException("Modelo sem 'TOTAL DO ATIVO' ou 'TOTAL DO PASSIVO E PATRIMÔNIO LÍQUIDO'");
        }
        Map<String, String> descricoes = new LinkedHashMap<>();
        itens.forEach(i -> descricoes.put(i.codigo(), i.descricao()));
        return new ModeloBalanco(detalhes, descricoes, totais, totalAtivo, totalPassivo, provaReal, linhaCab, colCodigo,
                colDescricao, colAtual, colAvAtual, colAnterior, colAvAnterior, colHorizontal);
    }

    private static Map<Integer, String> textos(Row row) {
        Map<Integer, String> m = new LinkedHashMap<>();
        for (Cell c : row) {
            if (c.getCellType() == CellType.STRING) {
                m.put(c.getColumnIndex(), Texto.normalizar(c.getStringCellValue()));
            }
        }
        return m;
    }

    static String texto(Cell c) {
        if (c == null) return "";
        return switch (c.getCellType()) {
            case STRING -> c.getStringCellValue();
            case NUMERIC -> {
                double d = c.getNumericCellValue();
                yield d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
            }
            default -> "";
        };
    }

    public List<LinhaModelo> detalhes() { return detalhes; }
    public Map<String, LinhaModelo> porCodigo() { return porCodigo; }
    public int linhaTotal(Grupo g) { return linhaTotal.get(g); }
    public int linhaTotalAtivo() { return linhaTotalAtivo; }
    public int linhaTotalPassivoPl() { return linhaTotalPassivoPl; }
    public int linhaProvaReal() { return linhaProvaReal; }
    public int linhaCabecalho() { return linhaCabecalho; }
    public int colCodigo() { return colCodigo; }
    public int colDescricao() { return colDescricao; }
    public int colAtual() { return colAtual; }
    public int colAvAtual() { return colAvAtual; }
    public int colAnterior() { return colAnterior; }
    public int colAvAnterior() { return colAvAnterior; }
    public int colHorizontal() { return colHorizontal; }

    /** Todos os códigos do modelo (inclusive os que só agrupam) e suas descrições. */
    public Map<String, String> descricoes() { return descricoes; }

    /** Linhas de detalhe de um grupo cuja descrição (sem acento, maiúscula) casa com a regex. */
    public List<LinhaModelo> detalhesPorDescricao(Grupo g, String regex) {
        return detalhes.stream().filter(d -> d.grupo() == g && Texto.normalizar(d.descricao()).matches(".*(" + regex + ").*")).toList();
    }

    /** Linhas de detalhe abaixo do código cuja descrição casa com a regex (ex.: realizável a longo prazo). */
    public List<LinhaModelo> detalhesAbaixoDe(String regexDescricaoPai) {
        return descricoes.entrySet().stream()
                .filter(e -> Texto.normalizar(e.getValue()).matches(".*(" + regexDescricaoPai + ").*"))
                .findFirst()
                .map(e -> detalhes.stream().filter(d -> d.codigo().startsWith(e.getKey() + ".")).toList())
                .orElse(List.of());
    }

    public List<LinhaModelo> detalhesDo(Grupo g) {
        return detalhes.stream().filter(d -> d.grupo() == g).toList();
    }
}
