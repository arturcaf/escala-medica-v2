/**
 * Cadastro automatico no app Escala Medica a partir do formulario
 * "Cadastro de Médicos para App de Escalas".
 *
 * Usa uma chave propria do formulario (FORMS_TOKEN), nao a senha do admin.
 * A chave so serve para cadastrar medico novo pelo endereco /api/cadastro-forms;
 * nao entra no app, nao mexe na escala e nao edita ninguem.
 *
 * INSTALAR (uma vez so, pelo computador):
 * 1. Abra o formulario no modo de edicao > menu ⋮ > "Editor de script".
 *    Apague tudo em Codigo.gs, cole este arquivo inteiro e salve.
 * 2. Escolha a funcao "gerarChave" no menu de cima e clique em Executar
 *    (o Google pede autorizacao na primeira vez: aceite). O "Registro de execucao"
 *    mostra a chave gerada; ela tambem fica salva nas propriedades do script.
 * 3. No Railway, servico web do app > Variables > New Variable:
 *      FORMS_TOKEN = (a chave do passo 2)
 *    O Railway reinicia o app sozinho.
 * 4. Volte ao editor e execute "cadastrarRespostasExistentes". O registro mostra
 *    quem foi cadastrado e quem ja existia.
 * 5. Execute "instalarAcionador". A partir dai cada nova resposta entra no app sozinha.
 *
 * Quem ja esta no app (mesmo telefone, mesmo CRM ou mesmo nome, ignorando acentos)
 * nao e cadastrado de novo, entao rodar o passo 4 outra vez nao duplica ninguem.
 * Para trocar a chave: rode "gerarChave" de novo e atualize FORMS_TOKEN no Railway.
 */

var APP_BASE_URL = 'https://web-production-899f5.up.railway.app';

function gerarChave() {
  var chave = Utilities.getUuid().replace(/-/g, '') + Utilities.getUuid().replace(/-/g, '');
  PropertiesService.getScriptProperties().setProperty('FORMS_TOKEN', chave);
  Logger.log('Chave gerada. Cole no Railway como FORMS_TOKEN:\n' + chave);
}

function instalarAcionador() {
  var form = FormApp.getActiveForm();
  ScriptApp.getProjectTriggers().forEach(function (t) {
    if (t.getHandlerFunction() === 'onFormSubmitEscala') ScriptApp.deleteTrigger(t);
  });
  ScriptApp.newTrigger('onFormSubmitEscala').forForm(form).onFormSubmit().create();
  Logger.log('Acionador instalado: cada nova resposta sera cadastrada no app.');
}

function cadastrarRespostasExistentes() {
  FormApp.getActiveForm().getResponses().forEach(cadastrarResposta_);
}

function onFormSubmitEscala(e) {
  cadastrarResposta_(e.response);
}

function cadastrarResposta_(resposta) {
  var chave = PropertiesService.getScriptProperties().getProperty('FORMS_TOKEN');
  if (!chave) {
    Logger.log('Falta a chave: rode "gerarChave" primeiro.');
    return;
  }
  var c = lerCampos_(resposta);
  if (!c.nome) {
    Logger.log('Resposta sem nome, ignorada.');
    return;
  }
  var resp = UrlFetchApp.fetch(APP_BASE_URL + '/api/cadastro-forms', {
    method: 'post',
    contentType: 'application/json',
    headers: { Authorization: 'Bearer ' + chave },
    payload: JSON.stringify(c),
    muteHttpExceptions: true
  });
  var dados = {};
  try {
    dados = JSON.parse(resp.getContentText());
  } catch (err) {
    dados = { error: 'resposta inesperada do app (HTTP ' + resp.getResponseCode() + ')' };
  }
  if (dados.status === 'criado') Logger.log('Cadastrado: ' + dados.nome + ' (id ' + dados.id + ')');
  else if (dados.status === 'existente') Logger.log('Ja existia: ' + c.nome + ' (no app como "' + dados.nome + '")');
  else Logger.log('Nao cadastrado: ' + c.nome + ' (' + (dados.error || resp.getResponseCode()) + ')');
}

function lerCampos_(resposta) {
  var c = { nome: '', email: '', crm: '', telefone: '' };
  resposta.getItemResponses().forEach(function (item) {
    var titulo = normalizar_(item.getItem().getTitle());
    var valor = String(item.getResponse() || '').trim();
    if (titulo.indexOf('nome') === 0) c.nome = valor;
    else if (titulo.indexOf('mail') >= 0) c.email = valor;
    else if (titulo.indexOf('crm') >= 0) c.crm = valor;
    else if (titulo.indexOf('telefone') >= 0) c.telefone = valor;
  });
  return c;
}

function normalizar_(s) {
  return String(s || '').normalize('NFD').replace(/[̀-ͯ]/g, '').replace(/\*/g, '').toLowerCase().trim();
}
