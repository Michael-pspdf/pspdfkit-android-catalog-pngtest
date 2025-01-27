/*
 *   Copyright © 2020-2025 PSPDFKit GmbH. All rights reserved.
 *
 *   The PSPDFKit Sample applications are licensed with a modified BSD license.
 *   Please see License for details. This notice may not be removed from this file.
 */

package com.pspdfkit.catalog.examples.kotlin

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.pspdfkit.catalog.R
import com.pspdfkit.catalog.SdkExample
import com.pspdfkit.configuration.activity.PdfActivityConfiguration
import com.pspdfkit.document.DocumentSource
import com.pspdfkit.document.PdfDocument
import com.pspdfkit.document.PdfDocumentLoader
import com.pspdfkit.document.providers.AssetDataProvider
import com.pspdfkit.signatures.DigitalSignatureMetadata
import com.pspdfkit.signatures.DigitalSignatureType
import com.pspdfkit.signatures.SignatureAppearance
import com.pspdfkit.signatures.SignatureGraphic
import com.pspdfkit.signatures.SignerOptions
import com.pspdfkit.signatures.SigningManager
import com.pspdfkit.signatures.getPrivateKeyEntryFromP12Stream
import com.pspdfkit.ui.PdfActivity
import com.pspdfkit.ui.PdfActivityIntentBuilder
import com.pspdfkit.utils.PdfLog
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * An example that shows how to digitally sign a PDF document using [SigningManager].
 * This is a Simple implementation where user provides Private key in [SignerOptions].
 */
class DigitalSignatureExample(context: Context) : SdkExample(context, R.string.digitalSignatureExampleTitle, R.string.digitalSignatureExampleDescription) {

    override fun launchExample(context: Context, configuration: PdfActivityConfiguration.Builder) {
        globalDigitalSignatureType = digitalSignatureType
        val intent = Intent(context, SignDocumentWithPngSuiteGraphicsActivity::class.java)
        context.startActivity(intent)
    }
}

const val SHOW_DOCUMENT_FOR_MS = 1000L
const val SHOW_RESULTS_FOR_MS = 0L
var globalDigitalSignatureType = DigitalSignatureType.CADES

class SignDocumentWithPngSuiteGraphicsActivity : AppCompatActivity() {
    private var testCaseIndex = -1
    private val results = mutableStateMapOf<Int, Boolean>()
    private val graphicsList = mutableListOf<String>()
    private val gridState = LazyGridState()

    private val launcher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        results[testCaseIndex] = result.resultCode == RESULT_OK
        signNextDocument()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        graphicsList.addAll(findAssets(GRAPHICS_FOLDER))

        setContent {
            val done = results.size == graphicsList.size
            val current = if (!done) {
                if (testCaseIndex > 0) "($testCaseIndex/${graphicsList.size})" else ""
            } else "- finished"
            Scaffold (
                topBar = { CenterAlignedTopAppBar( title = {Text("Signature Graphics Test $current")})}
            ) {

                Box(modifier = Modifier
                    .fillMaxSize()
                    .padding(it)) {
                    LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Fixed(3), // 2 columns
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp),
                    ) {
                        itemsIndexed(graphicsList) { i, item ->
                            var color = Color.Transparent
                            var icon = Icons.Filled.Check
                            results[i]?.let { success ->
                                color = if (success) Color.Green else Color.Red
                                icon = if (success) Icons.Filled.Check else Icons.Filled.Close
                            }
                            Row {
                                Text(String.format(Locale.getDefault(), "%3d.", i+1))
                                Spacer(modifier = Modifier.width(2.dp))
                                Icon( imageVector = icon, tint = color, contentDescription = "Image")
                                Text(item.removeSuffix(".png"))
                            }
                        }
                    }
                }
            }
        }

        if (graphicsList.isNotEmpty()) signNextDocument()
    }

    private fun findAssets(startFolder: String, supportedImages: List<String> = listOf(".png", ".jpg", ".jpeg" ), recurseSubFolders: Boolean = true): List<String> {
        fun String.isSupportedImage() = supportedImages.any { endsWith(it, ignoreCase = true) }

        val paths = mutableListOf<String>()

        fun searchAssets(directory: String) {
            try {
                val files = assets.list(directory) ?: return

                files.forEach { file ->
                    val fullPath = if (directory.isEmpty()) file else "$directory/$file"

                    if (file.isSupportedImage()) {
                        // Add the full path of the JPEG/JPG file to the list
                        paths.add(fullPath)
                    } else if (assets.list(fullPath)?.isNotEmpty() == true && recurseSubFolders) {
                        // If it's a directory, recursively search inside it
                        searchAssets(fullPath)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        searchAssets(startFolder)

        return paths

    }

    private fun signNextDocument() {
        ++testCaseIndex
        if (testCaseIndex in graphicsList.indices) {
            CoroutineScope(Dispatchers.Main).launch {
                delay(SHOW_RESULTS_FOR_MS)
                signAndShowDocument(graphicsList[testCaseIndex])
            }
        } else {
            PdfLog.d("DigitalSignatureExample", "Finished.")
        }
    }

    private fun signAndShowDocument(signatureGraphic: String){
        signDocument(signatureGraphic,
            onFailure = {
                results[testCaseIndex] = false
                signNextDocument()
            },
            onSuccess = {
                val intent = PdfActivityIntentBuilder.fromUri(this, it)
                    .configuration(PdfActivityConfiguration.Builder(this).build())
                    .activityClass(ShowSignedDocumentActivity::class.java)
                    .build()
                launcher.launch(intent)
            }
        )
    }


    private fun signDocument(signatureGraphic: String, onFailure: () -> Unit, onSuccess: (Uri) -> Unit) {
        val assetName = "Form_example.pdf"
        val context = this
        val unsignedDocument = PdfDocumentLoader.openDocument(context, DocumentSource(AssetDataProvider(assetName)))
        val keyEntryWithCertificates = getPrivateKeyEntry(context)
        val signatureFormFields = unsignedDocument.documentSignatureInfo.signatureFormFields
        val outputFile = File(context.filesDir, "signedDocument.pdf")
        outputFile.delete() // make sure output is deleted from previous runs.
        val signerOptions = SignerOptions.Builder(signatureFormFields[0], Uri.fromFile(outputFile))
            .setPrivateKey(keyEntryWithCertificates)
            .setType(globalDigitalSignatureType)
            .setSignatureMetadata(DigitalSignatureMetadata(SignatureAppearance(signatureWatermark = SignatureGraphic.fromBitmap(AssetDataProvider(signatureGraphic)))))
            .build()

        /** [SignerOptions] contains all the required configuration for [SigningManager]*/
        SigningManager.signDocument(
            context = context,
            signerOptions = signerOptions,
            onFailure = { e ->
                PdfLog.e("DigitalSignatureExample", e, "Error while signing document with $signatureGraphic.")
                CoroutineScope(Dispatchers.Main).launch {
                    delay(SHOW_DOCUMENT_FOR_MS)
                    onFailure()
                }
            }
        ) {
            onSuccess(outputFile.toUri())
        }
    }

    @Throws(IOException::class, GeneralSecurityException::class)
    private fun getPrivateKeyEntry(context: Context): KeyStore.PrivateKeyEntry {
        // Inside a p12 we have both the certificate (or certificate chain to the root CA) and private key used for signing.
        val keystoreFile = context.assets.open("digital-signatures/ExampleSigner.p12")
        return getPrivateKeyEntryFromP12Stream(keystoreFile, "test")
    }

    companion object {
        const val GRAPHICS_FOLDER = "signature-testimages"
    }
}

class ShowSignedDocumentActivity: PdfActivity() {

    override fun onDocumentLoaded(document: PdfDocument) {
        super.onDocumentLoaded(document)
        CoroutineScope(Dispatchers.Main).launch {
            delay(SHOW_DOCUMENT_FOR_MS)
            setResult(RESULT_OK)
            finish()
        }
    }

    override fun onDocumentLoadFailed(exception: Throwable) {
        super.onDocumentLoadFailed(exception)
        finishActivity(RESULT_CANCELED)
    }
}