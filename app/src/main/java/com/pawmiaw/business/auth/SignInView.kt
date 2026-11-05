package com.pawmiaw.business

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

//
//  Sign in with the PawMiaw account the business uses on the web (iOS SignInView.swift). A
//  business is created on the web (pawmiaw.com/providers); this app works in it.
//

@Composable
fun SignInView(modifier: Modifier = Modifier) {
    val session = SessionStore.shared
    val form = remember { FormState() }
    val email = remember { mutableStateOf("") }
    val password = remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var showReset by remember { mutableStateOf(false) }
    val openURL = rememberOpenURL()
    val scope = rememberCoroutineScope()

    fun signIn() {
        val mail = email.value.trim()
        val ok = form.check(
            listOf(
                "email" to (if (mail.isEmpty()) L("Write your email.") else if (mail.contains("@")) null else L("This email doesn't look right.")),
                "password" to (if (password.value.isEmpty()) L("Write your password.") else null),
            )
        )
        if (!ok) return
        working = true
        scope.launch {
            try {
                session.signIn(mail, password.value)
            } catch (e: CancellationException) {
                throw e
            } catch (e: APIError) {
                if (e.status == 401) {
                    form.errors = mapOf("password" to L("Invalid email or password."))
                    Toast.error(L("Invalid email or password."))
                } else {
                    form.apply(e, known = mapOf("email" to "email", "password" to "password"))
                }
            } catch (e: Exception) {
                form.apply(e, known = mapOf("email" to "email", "password" to "password"))
            } finally {
                working = false
            }
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .background(Theme.canvas)
            .systemBarsPadding()
            .imePadding()
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            FormScroll(form = form) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    Column(
                        Modifier.widthIn(max = 480.dp).fillMaxWidth().padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(22.dp),
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(top = 36.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            val markShape = RoundedCornerShape(26.dp)
                            Image(
                                painter = painterResource(R.drawable.business_mark),
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .size(108.dp)
                                    .shadow(18.dp, markShape, clip = false, ambientColor = Theme.blue.copy(alpha = 0.25f), spotColor = Theme.blue.copy(alpha = 0.25f))
                                    .clip(markShape),
                            )
                            Text(L("PawMiaw Business"), style = Theme.heading(30), color = Theme.inkStrong, textAlign = TextAlign.Center)
                            Text(
                                L("Your clinic, grooming salon, hotel or walks, from your phone."),
                                style = Theme.bodyFont,
                                color = Theme.inkSoft,
                                textAlign = TextAlign.Center,
                            )
                        }

                        Card(padding = 20.dp) {
                            // iOS `.textContentType(.username)` / `.password`: autofill is left to the field.
                            PMTextField(L("Email"), text = email, key = "email", form = form, prompt = "nombre@negocio.com", keyboard = KeyboardType.Email)
                            PMTextField(L("Password"), text = password, key = "password", form = form, secure = true)
                            PMButton(L("Sign in"), fullWidth = true, isWorking = working, modifier = Modifier.padding(top = 4.dp)) { signIn() }
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable(role = Role.Button) { showReset = true }
                                    .padding(vertical = 4.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(L("Forgot your password?"), style = Theme.text(15, FontWeight.Bold), color = Theme.blueText)
                            }
                        }

                        Column(
                            Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(L("Don't have your business on PawMiaw yet?"), style = Theme.smallFont, color = Theme.inkSoft, textAlign = TextAlign.Center)
                            Text(
                                L("Create it on pawmiaw.com"),
                                style = Theme.text(15, FontWeight.Bold),
                                color = Theme.blueText,
                                modifier = Modifier
                                    .clickable(role = Role.Button) { openURL(APIConfig.webURL("providers").toString()) }
                                    .padding(vertical = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
    if (showReset) {
        AppSheet(onDismissRequest = { showReset = false }) { ResetPasswordSheet(email = email.value) }
    }
}

@Composable
private fun ResetPasswordSheet(email: String) {
    val session = SessionStore.shared
    val dismiss = LocalDismiss.current
    val form = remember { FormState() }
    val mailState = remember { mutableStateOf(email) }
    var working by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    ProviderModal(
        title = L("Reset your password"),
        subtitle = L("We'll email you a link to choose a new one."),
        systemImage = "key.fill",
        accent = ModalAccent.butter,
        form = form,
        primaryTitle = L("Send link"),
        isWorking = working,
        onPrimary = {
            val mail = mailState.value.trim()
            if (form.check(listOf("email" to (if (mail.contains("@")) null else L("Write your email."))))) {
                working = true
                scope.launch {
                    try {
                        session.requestPasswordReset(mail)
                        Toast.show(L("If that email has an account, the link is on its way."))
                        dismiss()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        form.apply(e, known = mapOf("email" to "email"))
                    } finally {
                        working = false
                    }
                }
            }
        },
        modifier = Modifier.sheetPresentation(setOf(PresentationDetent.medium)),
    ) {
        PMTextField(L("Email"), text = mailState, key = "email", form = form, keyboard = KeyboardType.Email)
    }
}
