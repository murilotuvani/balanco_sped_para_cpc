# Balanço SPED ECD → modelo CPC 26

Aplicação de linha de comando (Java 21, Maven) que lê arquivos **SPED ECD** e preenche o modelo
`Modelo_Balanco_Patrimonial.xlsx` (Balanço Patrimonial + aba "Indicadores e Dashboard").

O relatório usa **exatamente as linhas e níveis do modelo**: nenhuma linha ou nível novo é criado.
Cada conta do SPED é somada numa das linhas de detalhe já existentes.

## Compilar

```bash
mvn package
```

Gera `target/balanco-sped-cpc.jar` (jar único, com dependências, modelo e regras embutidos).

## Executar

```bash
java -jar target/balanco-sped-cpc.jar ECD_2024.txt ECD_2025.txt --saida saida/Balanco_CPC_2025.xlsx
```

- A ordem dos arquivos não importa: o de data-base mais recente vira o **Exercício Atual** e o
  outro o **Exercício Anterior**. Um arquivo só também funciona (coluna anterior fica zerada).
- Opções: `--saida`, `--modelo` (outro modelo .xlsx com a mesma estrutura), `--mapeamento`
  (outro arquivo de regras), `--charset` (padrão ISO-8859-1), `--sem-detalhamento`.

Saídas:

1. **Planilha** no modelo, com valores, AV%, AH%, prova real, indicadores e gráficos.
2. **`<saida>_detalhamento.csv`**: cada conta analítica, a linha do modelo em que entrou e a regra
   que decidiu. Serve para conferir o balanço; abre direto no Excel.
3. **Resumo no console** com o balanço, os indicadores e avisos de conferência.

## Como os valores são apurados

1. Saldo final de cada conta analítica na data-base (registro **I155** do último período **I150**).
2. Grande grupo (AC, ANC, PC, PNC, PL) pelo **balanço entregue no próprio SPED** (J100, via a
   aglutinação I052 da conta). Sem isso, pelo **plano referencial** (I051) e depois pela natureza.
   Contas de resultado ainda com saldo (exercício não encerrado) vão para Lucros/Prejuízos
   Acumulados; contas de natureza 05/09 (compensação) ficam fora.
3. Linha do modelo pelas regras de `src/main/resources/mapeamento-padrao.csv`, na ordem: a
   primeira que casar vence, sempre dentro do grupo da conta. As regras olham as descrições da
   aglutinação J100, o plano de contas, o nome da conta e o código referencial. Cada grupo tem uma
   regra final de "demais contas". Uma regra que aponte para uma linha inexistente no modelo é
   recusada.

Para ajustar o enquadramento, copie o CSV, edite e passe com `--mapeamento`.

## Correções em relação ao modelo original

As fórmulas de total e de indicadores do modelo fornecido apontavam para linhas erradas (por
exemplo, "Total do Passivo Circulante" somava `D36:D41` e os indicadores usavam `D42` como passivo
circulante). A aplicação reescreve essas fórmulas com as referências certas, descobertas a partir
da própria estrutura da planilha. Os rótulos de ano do modelo (2026/2025) são trocados pelos anos
reais das datas-base.
