package br.com.tuvani.murilo.mapeamento;

import br.com.tuvani.murilo.Texto;
import br.com.tuvani.murilo.modelo.Grupo;
import br.com.tuvani.murilo.modelo.LinhaModelo;
import br.com.tuvani.murilo.modelo.ModeloBalanco;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Carrega as regras de enquadramento (CSV) e enquadra cada conta na primeira regra que casar.
 * Só aceita linhas que existem no modelo, e só dentro do mesmo grupo da conta: uma conta do
 * passivo circulante nunca vai parar numa linha do não circulante, por exemplo.
 */
public final class Mapeador {

    private final List<Regra> regras;
    private final ModeloBalanco modelo;

    private Mapeador(List<Regra> regras, ModeloBalanco modelo) {
        this.regras = regras;
        this.modelo = modelo;
    }

    public static Mapeador carregar(Reader csv, String nomeArquivo, ModeloBalanco modelo) throws IOException {
        List<Regra> regras = new ArrayList<>();
        List<String> erros = new ArrayList<>();
        try (BufferedReader in = new BufferedReader(csv)) {
            String linha;
            int n = 0;
            while ((linha = in.readLine()) != null) {
                n++;
                if (n == 1 && !linha.isEmpty() && linha.charAt(0) == '﻿') {
                    linha = linha.substring(1);
                }
                String t = linha.strip();
                if (t.isEmpty() || t.startsWith("#")) continue;
                String origem = nomeArquivo + ":" + n;
                String[] f = t.split(";", -1);
                if (f.length < 4) {
                    erros.add(origem + ": esperado grupo;campo;padrao;linha[;comentario]");
                    continue;
                }
                try {
                    regras.add(regra(f, origem, modelo));
                } catch (IllegalArgumentException e) {
                    erros.add(origem + ": " + e.getMessage());
                }
            }
        }
        if (!erros.isEmpty()) {
            throw new IllegalArgumentException("Erros no arquivo de mapeamento:\n  " + String.join("\n  ", erros));
        }
        for (Grupo g : Grupo.values()) {
            boolean temFallback = regras.stream().anyMatch(r -> (r.grupo() == null || r.grupo() == g)
                    && r.campos().equals(List.of(Regra.Campo.TODAS))
                    && modelo.porCodigo().get(r.linha()).grupo() == g);
            if (!temFallback) {
                throw new IllegalArgumentException("O mapeamento precisa de uma regra '*' (fallback) para o grupo " + g);
            }
        }
        return new Mapeador(List.copyOf(regras), modelo);
    }

    private static Regra regra(String[] f, String origem, ModeloBalanco modelo) {
        String g = f[0].trim();
        Grupo grupo = g.equals("*") || g.isEmpty() ? null : Grupo.valueOf(g.toUpperCase());
        String[] nomesCampos = f[1].trim().split("\\+");
        String[] padroes = f[2].split("&&", -1);
        if (nomesCampos.length != padroes.length) {
            throw new IllegalArgumentException("quantidade de campos (" + nomesCampos.length
                    + ") diferente da quantidade de padrões separados por && (" + padroes.length + ")");
        }
        List<Regra.Campo> campos = new ArrayList<>();
        List<Pattern> pats = new ArrayList<>();
        for (int i = 0; i < nomesCampos.length; i++) {
            String nc = nomesCampos[i].trim().toUpperCase();
            Regra.Campo campo = nc.equals("*") ? Regra.Campo.TODAS : Regra.Campo.valueOf(nc);
            campos.add(campo);
            try {
                String p = padroes[i].trim();
                // Descrições são comparadas sem acento e em maiúsculas: tira os acentos do padrão e
                // ignora caixa (sem mexer em escapes como \b ou \d).
                pats.add(Pattern.compile(Texto.semAcento(p), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
            } catch (PatternSyntaxException e) {
                throw new IllegalArgumentException("expressão regular inválida: " + e.getDescription());
            }
        }
        String linha = f[3].trim();
        LinhaModelo lm = modelo.porCodigo().get(linha);
        if (lm == null) {
            throw new IllegalArgumentException("a linha '" + linha + "' não existe no modelo como linha de valor"
                    + " (o relatório não cria níveis novos). Linhas válidas: " + modelo.porCodigo().keySet());
        }
        if (grupo != null && lm.grupo() != grupo) {
            throw new IllegalArgumentException("a linha " + linha + " é do grupo " + lm.grupo() + ", não de " + grupo);
        }
        String comentario = f.length > 4 ? f[4].trim() : "";
        return new Regra(grupo, List.copyOf(campos), List.copyOf(pats), linha, comentario, origem);
    }

    public Enquadramento enquadrar(ContaClassificada c) {
        for (Regra r : regras) {
            if (modelo.porCodigo().get(r.linha()).grupo() != c.grupo()) {
                continue;
            }
            if (r.aceita(c)) {
                return new Enquadramento(c, r.linha(), r);
            }
        }
        // Não acontece: carregar() garante um fallback por grupo.
        throw new IllegalStateException("Nenhuma regra para a conta " + c.conta().codigo());
    }

    public List<Regra> regras() {
        return regras;
    }
}
