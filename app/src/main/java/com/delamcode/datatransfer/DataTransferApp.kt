package com.delamcode.datatransfer

import android.app.Application

class DataTransferApp : Application() {
    val repository: Repo by lazy { Repo( dataModel = DataModel() ) } }